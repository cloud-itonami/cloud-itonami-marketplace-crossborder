(ns borderops.sim
  "Offline demo: quote a landed cost, watch a missing rate refuse to
  guess, and watch an HS classification wait for a human.
  `clojure -M:dev:run`."
  (:require [borderops.operation :as operation]
            [borderops.store :as store]
            [langgraph.graph :as g]))

(def ^:private ctx {:actor-id "border-demo" :phase 3})
(def ^:private product "gtin.05449000000996")

(defn- run-req! [actor tid request]
  (g/run* actor {:request request :context ctx} {:thread-id tid}))

(defn -main [& _]
  (let [s (store/seed-db)
        actor (operation/build s)]

    (println "\n=== 1. 日本向け着地コスト見積（自動コミット）===")
    (run-req! actor "sim-1" {:op :quote-landed-cost
                             :patch {:goods-minor 50000 :shipping-minor 2000
                                     :insurance-minor 1000 :destination "JPN"
                                     :hs6 "220210" :currency "JPY" :quote-id "q-jpn"}})
    (let [q (store/quote-record s "q-jpn")]
      (println "  課税価格  :" (:landed/customs-value-minor q))
      (println "  関税      :" (:landed/duty-minor q))
      (println "  付加価値税:" (:landed/vat-minor q) "(課税標準:" (:landed/vat-base q) ")")
      (println "  合計      :" (:landed/total-minor q) (:landed/currency q))
      (println "  出典      :" (:landed/rate-source q) "/" (:landed/rate-as-of q))
      (println "  見積である:" (:landed/estimate? q)))

    (println "\n=== 2. 税率表に無い仕向地（推測せず算出不能）===")
    (let [r (run-req! actor "sim-2" {:op :quote-landed-cost
                                     :patch {:goods-minor 50000 :destination "DEU"
                                             :hs6 "220210" :currency "JPY"
                                             :quote-id "q-deu"}})]
      (println "  status     :" (:status r) "(人間にすら聞かない)")
      (println "  violations :" (mapv :rule (:violations (last (store/ledger s)))))
      (println "  記録された見積:" (store/quote-record s "q-deu")))

    (println "\n=== 3. HS 分類は必ず人間が受理する ===")
    (let [held (run-req! actor "sim-3" {:op :propose-hs-classification
                                        :patch {:product product :hs6 "220210"
                                                :basis :broker-supplied :confidence 0.95}})]
      (println "  status     :" (:status held))
      (println "  分類済み   :" (store/classification s product) "（受理前）")
      (let [ok (g/run* actor {:approval {:status :approved :by "customs-01"}}
                       {:thread-id "sim-3" :resume? true})]
        (println "  --- 人間 customs-01 が受理 ---")
        (println "  status     :" (:status ok))
        (println "  分類済み   :" (:classification/hs6 (store/classification s product)))))

    (println "\n=== 4. 紛争は受付のみ。誰も裁定しない ===")
    (run-req! actor "sim-4" {:op :open-dispute
                             :patch {:id "disp-1" :order "ord-1" :buyer "b" :seller "s"
                                     :reason :not-received :narrative "届かない"
                                     :opened-at "2026-06-01T00:00:00Z"}})
    (run-req! actor "sim-5" {:op :add-dispute-evidence
                             :patch {:dispute-id "disp-1" :party :buyer
                                     :kind :photo :ref "r1" :filed-at "t1"}})
    (run-req! actor "sim-6" {:op :add-dispute-evidence
                             :patch {:dispute-id "disp-1" :party :seller
                                     :kind :tracking :ref "r2" :filed-at "t2"}})
    (let [d (store/dispute s "disp-1")]
      (println "  状態      :" (:dispute/state d))
      (println "  証跡      :" (mapv :evidence/party (:dispute/evidence d)))
      (println "  裁定      :" (:dispute/decision d) "← actor は永久に裁定しない"))

    (println "\n=== 監査台帳 ===")
    (doseq [f (store/ledger s)]
      (println " " (:t f) (:op f) (or (:basis f) "")))))
