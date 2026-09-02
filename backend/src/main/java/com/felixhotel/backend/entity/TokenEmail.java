package com.felixhotel.backend.entity;

import com.felixhotel.backend.entity.enums.TipoCodifica;
import com.felixhotel.backend.entity.enums.TipoDocumento;
import com.felixhotel.backend.entity.enums.TipoTokenEmail;
import com.felixhotel.backend.security.TipoAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Un segreto mandato per email, che scade e vale una volta sola.
 *
 * <p>Serve a tre cose — confermare un indirizzo, accettare un invito, resettare una
 * password — e sono tre usi della stessa cosa, non tre cose: il perche' della tabella
 * unica sta nel V14__token_email.sql, le tre durate su {@link TipoTokenEmail}.
 *
 * <p><b>E' una credenziale, e va trattata come tale.</b> Chi ha in mano un token di
 * reset prende l'account senza sapere la password: e' per questo che in tabella non
 * finisce il token ma la sua {@link #tokenHash impronta}, e che nessuna risposta
 * dell'API lo restituisce mai. Il token esiste in chiaro in due soli posti — dentro il
 * link dell'email, e per un istante in memoria mentre lo si genera.
 *
 * <p><b>Non ha nessuna relazione JPA verso il suo destinatario</b>, e non e' una
 * dimenticanza: i clienti stanno in {@code utente} e il personale in {@code staff}, due
 * tabelle diverse, quindi un {@code @ManyToOne} dovrebbe puntare a una o all'altra a
 * seconda della riga — cosa che JPA non sa fare e il database nemmeno. La coppia
 * {@link #tipoAccount} + {@link #soggettoId} e' la stessa forma che
 * {@code AppUserPrincipal} usa dal 2026-08-27 per rispondere alla domanda <i>chi sta
 * chiamando</i>, e per la stessa ragione: <b>l'id da solo non identifica nessuno</b>.
 */
@Entity
@Table(name = "token_email")
@Getter
@Setter
@NoArgsConstructor
public class TokenEmail extends BaseAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * A cosa serve. {@code EnumType.STRING} come ovunque, e col {@code CHECK} in
     * database: l'elenco lo scrive l'applicazione, quindi un valore fuori elenco
     * sarebbe un difetto nostro — stesso criterio di {@link TipoCodifica} e opposto a
     * quello di {@link TipoDocumento}.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TipoTokenEmail tipo;

    /**
     * In quale tabella vive il destinatario.
     *
     * <p><b>Riusa l'enum del package {@code security}</b> invece di dichiararne uno
     * gemello qui, e la scelta merita una riga perche' attraversa un confine: la
     * domanda "cliente o personale?" e' <i>una sola</i> in tutto il progetto, e due
     * enum identici in due package vorrebbero dire due elenchi da tenere allineati a
     * mano — cioe' il difetto che il progetto evita ovunque altrove. Se un giorno i due
     * elenchi dovessero divergere, e' qui che si spacchettano.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_account", nullable = false, length = 20)
    private TipoAccount tipoAccount;

    /** L'id del destinatario dentro la sua tabella. Da solo non identifica nessuno. */
    @Column(name = "soggetto_id", nullable = false)
    private Long soggettoId;

    /**
     * L'impronta SHA-256 del token, in esadecimale.
     *
     * <p>Il perche' non sia il token, e il perche' non sia BCrypt, stanno nel V14: in
     * breve, un backup letto da chi non doveva non deve valere il controllo di ogni
     * account con un reset in corso, e il costo di BCrypt difende da un attacco —
     * indovinare un segreto scelto da una persona — che qui non esiste, perche' questo
     * segreto lo sceglie {@code SecureRandom} e ha 256 bit.
     */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** Quando smette di valere. La durata la decide {@link TipoTokenEmail}. */
    @Column(nullable = false)
    private LocalDateTime scadenza;

    /**
     * Quando e' stato usato, oppure {@code null} se non lo e' ancora stato.
     *
     * <p><b>Il token si consuma e non si cancella</b>: un secondo clic sullo stesso
     * link deve poter rispondere <i>"questo link e' gia' stato usato"</i>, che e'
     * un'informazione, invece di <i>"questo link non esiste"</i>, che manderebbe chi
     * legge a cercare il problema dalla parte sbagliata.
     */
    @Column(name = "usato_il")
    private LocalDateTime usatoIl;

    /**
     * Se questo token si possa ancora usare, alla data indicata.
     *
     * <p>Sta sull'entita' e non in chi la legge per la stessa ragione per cui
     * {@code StatoPrenotazione.occupaCamera()} sta sullo stato: e' una proprieta' del
     * valore, non una decisione di chi lo usa, e averla in un posto solo evita che due
     * chiamanti la scrivano in due modi leggermente diversi.
     *
     * <p>Il confronto sulla scadenza e' <b>stretto</b>: un token che scade adesso e'
     * scaduto. Sul limite si sbaglia dalla parte severa.
     */
    public boolean utilizzabile(LocalDateTime adesso) {
        return usatoIl == null && scadenza.isAfter(adesso);
    }
}
