package com.felixhotel.backend.entity;

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

/**
 * Una voce di una tabella di codifica ministeriale: un codice con la sua
 * descrizione.
 *
 * <p><b>Non e' un'entita' di dominio ma un dato di riferimento</b>, ed e' una
 * differenza che si vede in tutto quel che segue: nessuna relazione punta a lei,
 * nessuna regola di business la usa, e il suo contenuto non lo decide questa
 * applicazione. E' un elenco che qualcun altro pubblica e che noi teniamo a
 * portata di mano perche' serve a compilare un modulo di legge.
 *
 * <p><b>Perche' sta in una tabella e non nel codice</b>: e' la quarta riga della
 * regola 24, nata il 2026-09-01. Non la cambia l'albergatore e non la cambia chi
 * installa, ma cambia lo stesso — i comuni si fondono, gli stati nascono — quindi
 * una costante Java vorrebbe dire un rilascio ad ogni fusione. E non la si digita
 * a mano, perche' e' un dato che deve solo essere <i>esatto</i>: si aggiorna in
 * blocco, con il file che il Ministero pubblica.
 *
 * <p><b>Le righe non ci sono all'installazione</b>, e non e' un lavoro lasciato a
 * meta': i valori veri stanno sul portale Alloggiati e inventarli qui vorrebbe
 * dire scrivere dati falsi nella tabella che esiste per essere esatta. La riempie
 * l'ADMIN con {@code PUT /api/codifiche/{tipo}} al primo avvio.
 */
@Entity
@Table(name = "voce_codifica")
@Getter
@Setter
@NoArgsConstructor
public class VoceCodifica extends BaseAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * A quale dei quattro elenchi appartiene.
     *
     * <p>{@code EnumType.STRING} come ovunque nel progetto, e con in piu' un
     * {@code CHECK} in database — che {@link TipoDocumento} non ha. La differenza
     * e' scritta su {@link TipoCodifica}: questo elenco lo scrive l'applicazione,
     * quello lo cambia la Questura.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TipoCodifica tipo;

    /**
     * Il codice come lo pubblica il Ministero.
     *
     * <p><b>Testo e non numero</b>, anche dove sembra un numero: sono stringhe con
     * gli zeri davanti, e un intero se li mangerebbe restituendo un codice che il
     * servizio non riconosce.
     *
     * <p>Unico dentro la sua famiglia, con un confronto che <b>distingue le
     * maiuscole</b> — come gli url delle foto e i numeri di documento: non e' un
     * nome che una persona scrive a modo suo, e' una stringa emessa da
     * un'autorita'. Fra famiglie diverse invece coincidere e' normale, perche' sono
     * elenchi che non si parlano.
     */
    @Column(nullable = false, length = 20)
    private String codice;

    /** Come si chiama: "ROMA", "FRANCIA", "CARTA DI IDENTITA'", "Capofamiglia". */
    @Column(nullable = false, length = 150)
    private String descrizione;

    /**
     * La sigla della provincia, <b>solo per i comuni</b>.
     *
     * <p>E' l'unica colonna che distingue le quattro famiglie, ed e' anche il
     * motivo per cui la tabella resta una sola: una differenza di una colonna non
     * giustifica quattro entita', quattro repository e quattro endpoint.
     *
     * <p>Serve a disambiguare gli omonimi, che in Italia sono tanti. Senza, una
     * tendina con tre "San Giovanni" identici obbligherebbe chi compila a
     * indovinare.
     */
    @Column(length = 2)
    private String provincia;
}
