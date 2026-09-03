-- Da quando i token di accesso emessi prima non valgono piu'.
--
-- **Chiude il debito piu' vecchio del progetto** (aperto il 2026-08-06): cambiare la
-- password non buttava fuori chi era gia' entrato. E' il comportamento naturale di un JWT
-- — se lo verifica con la firma e nient'altro, quindi resta valido finche' non scade — e
-- fino al V14 pesava poco, perche' l'unico modo di cambiare una password era che un ADMIN
-- lo facesse per qualcun altro. Col reset via email pesa molto di piu': chi si accorge di
-- essere stato derubato dell'account cambia la password **proprio per buttare fuori il
-- ladro**, ed e' l'unica cosa che non succedeva.
--
-- **Un istante e non una lista di token revocati.** Una lista sarebbe la risposta generale
-- — permette di revocarne uno solo — ma chiede una tabella che cresce, qualcuno che la
-- ripulisca e una lettura in piu' ad ogni richiesta. Qui la domanda vera e' sempre la
-- stessa, *"tutti quelli di prima"*, e a quella un istante risponde con una colonna e
-- **nessuna query aggiuntiva**: il filtro JWT rilegge gia' l'account ad ogni richiesta per
-- controllare che sia attivo, quindi questo valore arriva insieme, gratis.
--
-- **NULL vuol dire "non e' mai stato revocato niente"**, ed e' il caso di tutti gli account
-- che esistono oggi: nessun token in circolazione va invalidato dal fatto che questa
-- colonna sia nata.
ALTER TABLE utente ADD COLUMN token_non_validi_prima_di TIMESTAMP;
ALTER TABLE staff  ADD COLUMN token_non_validi_prima_di TIMESTAMP;

-- Nessun indice, ed e' voluto: questa colonna non si cerca mai: si legge insieme alla riga
-- dell'account, che il filtro carica gia' per email ad ogni richiesta.
