(ns borderops.edge.worker
  "The cross-border actor's Worker.

  Two things this host will not do, both permanent:

    - it files no customs declaration;
    - it decides no dispute.

  The second is the fleet-wide invariant: no actor adjudicates. This
  host can OPEN a dispute (asserting that someone complained, which is
  a fact) and ADD evidence to one; it has no op that closes one, and the
  support bridge that refers a case here cannot carry an outcome, a
  fault or a liability with it.

  On tariffs, the honest part: NO rate table ships with this actor. An
  empty table means `:landed/computable? false` and the governor
  HARD-blocks a quote rather than letting a guessed duty rate reach a
  buyer. Rates are operator input through `/rates`, and an HS
  classification is a PROPOSAL that always escalates — deciding what a
  good is, for customs purposes, is not a machine's call."
  (:require [marketplace.crossborder :as cb]
            [marketplace.edge :as edge]
            [borderops.advisor :as advisor]
            [borderops.governor :as governor]
            [borderops.phase :as phase]
            [borderops.store :as store]))

(def ^:private ops
  {:advise      (fn [st req] (advisor/-advise (advisor/mock-advisor) st req))
   :check       governor/check
   :disposition phase/verdict->disposition
   :gate        phase/gate
   :commit!     (fn [st proposal req]
                  (store/commit-record! st {:op (:op proposal)
                                            :subject (:subject req)
                                            :value (:value proposal)
                                            :payload (:value proposal)}))
   :ledger!     store/append-ledger!
   :hold-fact   governor/hold-fact})

(defn- ctx [body]
  {:actor-id "borderops-edge"
   :phase (get body "phase" 3)
   :now (get body "now" "2026-06-01T00:00:00Z")})

(defn- run [client wants body op patch ref]
  (edge/with-store
    {:client client :wants wants :store-fn store/kotobase-store}
    (fn [st]
      (edge/outcome ref (edge/run ops st (ctx body)
                                  {:op op :subject ref :ref ref :patch patch})))))

(defn- set-rates
  "The operator's duty/VAT table. Operator input, and the ONLY source of
  a rate this actor will quote from — there is no fallback, no default
  and no interpolation, because a plausible-looking wrong tariff is
  worse for a buyer than an admitted gap."
  [client body]
  (edge/with-store
    {:client client :wants {:rate :all} :store-fn store/kotobase-store}
    (fn [st]
      ;; `source` and `as-of` are REQUIRED by
      ;; `marketplace.crossborder/duty-rate-errors`: an unattributed,
      ;; undated rate is exactly the number that turns out wrong later
      ;; and cannot be audited. The host does not supply defaults for
      ;; them -- a missing citation must fail, not be invented.
      (let [rows (mapv (fn [r] {:destination (get r "destination")
                                :hs6 (get r "hs6")
                                :ad-valorem-bps (get r "ad-valorem-bps")
                                :vat-bps (get r "vat-bps")
                                :de-minimis-minor (get r "de-minimis-minor")
                                :source (get r "source")
                                :as-of (get r "as-of")})
                       (get body "rows" []))
            bad (vec (for [r rows
                           :let [errs (cb/duty-rate-errors (cb/duty-rate r))]
                           :when (seq errs)]
                       {:row (str (:destination r) "/" (:hs6 r))
                        :errors (mapv (comp name :crossborder.error/code) errs)}))]
        ;; ALL or NOTHING. Writing the valid rows and reporting the rest
        ;; would leave the table half-applied, and a rate table that is
        ;; partly the operator's intent is worse than one that is none of
        ;; it -- the quotes it produces look authoritative either way.
        (if (seq bad)
          {:ref "rates" :disposition "hold" :violations ["invalid-rate-row"]
           :rejected bad}
          (do (store/with-rates st rows)
              {:ref "rates" :disposition "commit" :violations []
               :rows (count rows)}))))))

;; ───────────────────────── routes ─────────────────────────

(defn- gated [request env f]
  (if-not (edge/authorised? request env)
    (js/Promise.resolve (edge/json {:error "unauthorised"} 401))
    (-> (.json request) (.then #(f (js->clj %))) (.then #(edge/json % 200)))))

(defn- routes [client request env method path _url]
  (cond
    (and (= method "POST") (= path "/rates")) (gated request env #(set-rates client %))

    (and (= method "POST") (= path "/classifications"))
    (gated request env
           (fn [b] (run client {:classification :all} b :propose-hs-classification
                        {:product (get b "product") :hs6 (get b "hs6")
                         :basis (get b "basis")}
                        (get b "product"))))

    (and (= method "POST") (= path "/quotes"))
    (gated request env
           (fn [b] (run client {:rate :all :classification :all :quote :all}
                        b :quote-landed-cost
                        {:quote-id (get b "quote-id")
                         :goods-minor (get b "goods-minor")
                         :shipping-minor (get b "shipping-minor" 0)
                         :insurance-minor (get b "insurance-minor" 0)
                         :destination (get b "destination")
                         :hs6 (get b "hs6")
                         :currency (get b "currency" "JPY")}
                        (or (get b "quote-id") (get b "hs6")))))

    (and (= method "POST") (= path "/disputes"))
    (gated request env
           (fn [b] (run client {:dispute :all} b :open-dispute
                        {:dispute-id (get b "dispute-id")
                         :order (get b "order")
                         :reason (keyword (get b "reason" "other"))
                         :opened-by (get b "opened-by")}
                        (get b "dispute-id"))))

    (and (= method "GET") (= path "/quotes"))
    (-> (edge/read-all client :quote)
        (.then (fn [qs]
                 (edge/json {:quotes (mapv (fn [q] {:quote-id (:quote/id q)
                                                    :computable? (get-in q [:quote/value :landed/computable?])
                                                    :total-minor (get-in q [:quote/value :landed/total-minor])})
                                           qs)}
                            200))))

    ;; /escalations and /ledger, implemented once in marketplace.edge.
    ;; Every high-stakes move in this actor escalates rather than committing
    ;; on a machine's say-so; without a way to READ those, each of those gates
    ;; is a black hole.
    :else (edge/ledger-routes client request env method path :borderops)))

(def app
  (clj->js
   {:fetch (fn [request env _ctx]
             (edge/serve "cloud-itonami-marketplace-crossborder" request env routes))}))
