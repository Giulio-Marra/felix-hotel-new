-- L'email di un account e' unica a meno delle maiuscole, su entrambe le
-- popolazioni: i clienti in `utente` e il personale in `staff`.
--
-- Nel V1 il vincolo era un UNIQUE sulla colonna, quindi case-sensitive:
-- "Mario@hotel.it" e "mario@hotel.it" entravano come due account distinti. Per
-- chi li scrive sono lo stesso indirizzo — nessuno si ricorda con che
-- maiuscole si e' registrato — e il danno non e' estetico: l'email e' la
-- credenziale di login, e CustomUserDetailsService la cerca per valore esatto.
-- Con due righe simili una sola delle due e' raggiungibile da chi digita
-- l'indirizzo a mano, e quale delle due lo decide il tasto shift.
--
-- E' la stessa cura gia' data ai nomi in V2, V3 e V4, arrivata qui dopo perche'
-- finche' gli account del personale si creavano con una INSERT il duplicato lo
-- si poteva produrre solo di proposito. Da POST /api/staff non piu': e' il
-- secondo endpoint che scrive un'email, ed e' il motivo per cui il vincolo si
-- sistema adesso invece che alla prossima occasione.
--
-- Il vincolo del V1 va tolto e non solo affiancato: lasciarlo vorrebbe dire
-- tenere in piedi due regole diverse sulla stessa colonna, di cui la piu'
-- debole non aggiunge niente. Il nome del constraint e' quello che Postgres
-- assegna a un UNIQUE dichiarato inline (<tabella>_<colonna>_key).
--
-- **Non converte i dati esistenti**: se in questo momento esistessero gia' due
-- indirizzi uguali a meno delle maiuscole, la creazione dell'indice fallirebbe
-- e la migration si fermerebbe qui. E' il comportamento voluto — quei due
-- account vanno guardati da una persona, non fusi da uno script che sceglie da
-- solo quale tenere.
ALTER TABLE utente DROP CONSTRAINT utente_email_key;
CREATE UNIQUE INDEX uq_utente_email ON utente (lower(email));

ALTER TABLE staff DROP CONSTRAINT staff_email_key;
CREATE UNIQUE INDEX uq_staff_email ON staff (lower(email));
