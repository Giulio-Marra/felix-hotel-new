-- L'indirizzo segreto da cui un canale esterno legge il calendario di una camera.
--
-- **Perche' una colonna sulla camera e non sulla tipologia.** iCal non sa esprimere le
-- quantita': un calendario dice "occupato dal 3 al 5", non "due unita' su tre occupate".
-- Un feed per tipologia verrebbe quindi letto da Booking come *"tutta la tipologia e'
-- piena"* al primo soggiorno venduto, e farebbe risultare esaurite camere libere. Un
-- calendario corrisponde a **una unita' vendibile**, e da noi l'unita' vendibile e' la
-- camera fisica.
--
-- **Perche' un token e non l'id.** Il feed lo scarica Booking, che non si autentica: e'
-- una rotta pubblica per costruzione, e l'unica difesa possibile e' che l'indirizzo non
-- si indovini. Con `/api/calendario/12.ics` chiunque leggerebbe il tasso di riempimento
-- dell'albergo provando i numeri da uno in su.
--
-- **Si rigenera**, ed e' la ragione per cui e' una colonna e non un valore derivato
-- dall'id: se un indirizzo finisce dove non doveva, si cambia il token e i vecchi link
-- smettono di valere. E' anche il motivo per cui nasce NULL — un feed esiste solo per le
-- camere che qualcuno ha deciso di pubblicare.
ALTER TABLE camera ADD COLUMN token_calendario VARCHAR(64);

-- Due camere non possono avere lo stesso indirizzo, e la lettura del feed cerca
-- **esattamente per token**: questo indice e' insieme il vincolo e la query.
CREATE UNIQUE INDEX uq_camera_token_calendario
    ON camera (token_calendario)
    WHERE token_calendario IS NOT NULL;
