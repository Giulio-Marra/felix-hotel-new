-- Schema iniziale Felix Hotel.
-- Riferimento funzionale completo: docs/ANALISI_FUNZIONALE.md (non versionato su Git).

-- ============================================================
-- RUOLO
-- ============================================================
CREATE TABLE ruolo (
    id          BIGSERIAL PRIMARY KEY,
    nome        VARCHAR(50)  NOT NULL UNIQUE,
    descrizione VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);

-- ============================================================
-- UTENTE (cliente frontoffice)
-- ============================================================
CREATE TABLE utente (
    id                  BIGSERIAL PRIMARY KEY,
    nome                VARCHAR(100)  NOT NULL,
    cognome             VARCHAR(100)  NOT NULL,
    email               VARCHAR(255)  NOT NULL UNIQUE,
    password_hash       VARCHAR(255)  NOT NULL,
    telefono            VARCHAR(30),
    data_nascita        DATE,
    data_registrazione  TIMESTAMP     NOT NULL DEFAULT now(),
    attivo              BOOLEAN       NOT NULL DEFAULT true,
    email_verificata    BOOLEAN       NOT NULL DEFAULT false,
    consenso_privacy    BOOLEAN       NOT NULL DEFAULT false,
    data_consenso       TIMESTAMP,
    ruolo_id            BIGINT        NOT NULL REFERENCES ruolo(id),
    created_at          TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE INDEX idx_utente_ruolo ON utente(ruolo_id);

-- ============================================================
-- STAFF (personale backoffice)
-- ============================================================
CREATE TABLE staff (
    id               BIGSERIAL PRIMARY KEY,
    nome             VARCHAR(100)  NOT NULL,
    cognome          VARCHAR(100)  NOT NULL,
    email            VARCHAR(255)  NOT NULL UNIQUE,
    password_hash    VARCHAR(255)  NOT NULL,
    telefono         VARCHAR(30),
    data_assunzione  DATE,
    attivo           BOOLEAN       NOT NULL DEFAULT true,
    ruolo_id         BIGINT        NOT NULL REFERENCES ruolo(id),
    created_at       TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE INDEX idx_staff_ruolo ON staff(ruolo_id);

-- ============================================================
-- TIPOLOGIA_CAMERA
-- ============================================================
CREATE TABLE tipologia_camera (
    id            BIGSERIAL PRIMARY KEY,
    nome          VARCHAR(100)   NOT NULL,
    descrizione   TEXT,
    capienza_max  INT            NOT NULL CHECK (capienza_max > 0),
    prezzo_notte  NUMERIC(10,2)  NOT NULL CHECK (prezzo_notte >= 0),
    created_at    TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP      NOT NULL DEFAULT now()
);

-- ============================================================
-- CAMERA
-- ============================================================
CREATE TABLE camera (
    id                    BIGSERIAL PRIMARY KEY,
    numero                VARCHAR(20)  NOT NULL UNIQUE,
    piano                 INT          NOT NULL,
    tipologia_camera_id   BIGINT       NOT NULL REFERENCES tipologia_camera(id),
    stato                 VARCHAR(20)  NOT NULL DEFAULT 'LIBERA'
        CHECK (stato IN ('LIBERA', 'OCCUPATA', 'MANUTENZIONE', 'PULIZIA')),
    created_at            TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_camera_tipologia ON camera(tipologia_camera_id);

-- ============================================================
-- DOTAZIONE + relazione many-to-many con TIPOLOGIA_CAMERA
-- ============================================================
CREATE TABLE dotazione (
    id          BIGSERIAL PRIMARY KEY,
    nome        VARCHAR(100)  NOT NULL UNIQUE,
    descrizione VARCHAR(255),
    created_at  TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE TABLE tipologia_camera_dotazione (
    tipologia_camera_id  BIGINT NOT NULL REFERENCES tipologia_camera(id) ON DELETE CASCADE,
    dotazione_id         BIGINT NOT NULL REFERENCES dotazione(id) ON DELETE CASCADE,
    PRIMARY KEY (tipologia_camera_id, dotazione_id)
);

-- ============================================================
-- MEDIA_CAMERA (foto delle tipologie di camera)
-- ============================================================
CREATE TABLE media_camera (
    id                    BIGSERIAL PRIMARY KEY,
    tipologia_camera_id   BIGINT        NOT NULL REFERENCES tipologia_camera(id) ON DELETE CASCADE,
    url                   VARCHAR(500)  NOT NULL,
    ordine                INT           NOT NULL DEFAULT 0,
    created_at            TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE INDEX idx_media_camera_tipologia ON media_camera(tipologia_camera_id);

-- ============================================================
-- PRENOTAZIONE
-- ============================================================
CREATE TABLE prenotazione (
    id                    BIGSERIAL PRIMARY KEY,
    utente_id             BIGINT         NOT NULL REFERENCES utente(id),
    tipologia_camera_id   BIGINT         NOT NULL REFERENCES tipologia_camera(id),
    camera_id             BIGINT         REFERENCES camera(id),
    data_check_in         DATE           NOT NULL,
    data_check_out        DATE           NOT NULL,
    numero_ospiti         INT            NOT NULL CHECK (numero_ospiti > 0),
    stato                 VARCHAR(20)    NOT NULL DEFAULT 'IN_ATTESA'
        CHECK (stato IN ('IN_ATTESA', 'CONFERMATA', 'CHECK_IN', 'CHECK_OUT', 'ANNULLATA')),
    canale                VARCHAR(20)    NOT NULL
        CHECK (canale IN ('ONLINE', 'TELEFONO', 'WALK_IN', 'AGENZIA')),
    gestita_da_staff_id   BIGINT         REFERENCES staff(id),
    importo_totale        NUMERIC(10,2)  NOT NULL CHECK (importo_totale >= 0),
    motivo_cancellazione  VARCHAR(255),
    data_cancellazione    TIMESTAMP,
    note                  VARCHAR(1000),
    created_at            TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP      NOT NULL DEFAULT now(),
    CONSTRAINT chk_prenotazione_date CHECK (data_check_out > data_check_in)
);

CREATE INDEX idx_prenotazione_utente ON prenotazione(utente_id);
CREATE INDEX idx_prenotazione_tipologia ON prenotazione(tipologia_camera_id);
CREATE INDEX idx_prenotazione_camera ON prenotazione(camera_id);
CREATE INDEX idx_prenotazione_staff ON prenotazione(gestita_da_staff_id);
-- Usato per la query di disponibilita': tipologia + range di date + stato.
CREATE INDEX idx_prenotazione_disponibilita ON prenotazione(tipologia_camera_id, data_check_in, data_check_out, stato);

-- ============================================================
-- OSPITE
-- ============================================================
CREATE TABLE ospite (
    id                BIGSERIAL PRIMARY KEY,
    prenotazione_id   BIGINT        NOT NULL REFERENCES prenotazione(id) ON DELETE CASCADE,
    nome              VARCHAR(100)  NOT NULL,
    cognome           VARCHAR(100)  NOT NULL,
    tipo_documento    VARCHAR(30)   NOT NULL,
    numero_documento  VARCHAR(50)   NOT NULL,
    data_nascita      DATE,
    created_at        TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE INDEX idx_ospite_prenotazione ON ospite(prenotazione_id);

-- ============================================================
-- IMPOSTAZIONI_HOTEL (riga singola, gestita in scrittura solo da ADMIN)
-- ============================================================
CREATE TABLE impostazioni_hotel (
    id                          BIGSERIAL PRIMARY KEY,
    nome                        VARCHAR(200)  NOT NULL,
    indirizzo                   VARCHAR(300),
    telefono                    VARCHAR(30),
    email                       VARCHAR(255),
    orario_check_in_default     TIME          NOT NULL,
    orario_check_out_default    TIME          NOT NULL,
    created_at                  TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMP     NOT NULL DEFAULT now()
);

-- Dati di riferimento minimi per far partire l'app.
INSERT INTO ruolo (nome, descrizione) VALUES
    ('ADMIN', 'Amministratore backoffice, accesso completo'),
    ('STAFF', 'Personale backoffice'),
    ('USER', 'Cliente che prenota');