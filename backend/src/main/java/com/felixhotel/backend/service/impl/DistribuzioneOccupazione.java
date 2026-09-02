package com.felixhotel.backend.service.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decide <b>quale</b> camera risulta occupata, quando si sa solo <b>quante</b>.
 *
 * <p><b>Perche' esiste.</b> In questo progetto una prenotazione e' per <i>tipologia</i>:
 * la camera fisica gliela assegna il check-in, quindi fino a quel momento sappiamo che
 * due doppie su tre sono vendute ma non quali. iCal pero' non sa esprimere le quantita' —
 * un calendario dice "occupato dal 3 al 5", non "due unita' su tre" — e un feed per
 * tipologia verrebbe letto da Booking come <i>tutta la tipologia e' piena</i> al primo
 * soggiorno venduto. Serve quindi un feed per camera, e quindi serve decidere quale.
 *
 * <p><b>La regola: si distribuisce, e cio' che e' gia' deciso non si tocca.</b> Per ogni
 * notte, le camere gia' fissate — una bloccata per manutenzione, una assegnata da un
 * check-in — restano dove sono; le occupazioni che una camera non ce l'hanno si
 * spalmano sulle rimanenti, <b>in ordine stabile</b>. Il totale che il canale vede e'
 * quindi sempre giusto, anche quando la singola stanza non e' quella su cui dormira'
 * davvero qualcuno.
 *
 * <p><b>Perche' l'ordine stabile conta piu' di quanto sembri.</b> Se la scelta cambiasse
 * fra due generazioni del feed, Booking vedrebbe la camera 1 liberarsi e la 2 occuparsi
 * senza che sia successo niente — e in mezzo, per un istante, potrebbe rivendere. Con
 * l'ordine per id il feed di ieri e quello di oggi coincidono finche' non cambia
 * l'occupazione vera.
 *
 * <p><b>Cosa succede in overbooking</b>, cioe' quando le unita' occupate sono piu' delle
 * camere: si marcano tutte e non si solleva niente. Non e' un dato incoerente da
 * rifiutare — e' un albergo che ha venduto piu' di quanto ha, cosa che capita — e la
 * risposta giusta e' dire al canale che <b>non c'e' piu' niente</b>, che e' esattamente
 * quel che serve per non peggiorare la situazione.
 *
 * <p>Classe pura: nessuna entita', nessun database, nessun orologio. E' tutta la ragione
 * per cui si puo' provare a fondo — vedi {@code DistribuzioneOccupazioneTest}.
 */
public final class DistribuzioneOccupazione {

    private DistribuzioneOccupazione() {
        // Solo metodi statici: e' un calcolo, non un oggetto.
    }

    /**
     * Le notti occupate di ogni camera, gia' compattate in periodi contigui.
     *
     * @param camere      gli id delle camere della tipologia, <b>in ordine stabile</b>;
     *                    l'ordine e' la scelta, quindi chi chiama deve garantirlo
     * @param occupazioni prenotazioni e blocchi che toccano l'orizzonte, ognuno per una
     *                    unita' sola
     * @param da          primo giorno dell'orizzonte, incluso
     * @param a           ultimo giorno dell'orizzonte, escluso
     * @return per ogni camera, i periodi in cui risulta occupata; le camere senza
     *         occupazioni non compaiono affatto
     */
    public static Map<Long, List<Periodo>> distribuisci(List<Long> camere,
                                                        List<UnitaOccupata> occupazioni,
                                                        LocalDate da, LocalDate a) {
        Map<Long, List<LocalDate>> nottiPerCamera = new HashMap<>();

        for (LocalDate notte = da; notte.isBefore(a); notte = notte.plusDays(1)) {
            for (Long camera : occupateInQuellaNotte(camere, occupazioni, notte)) {
                nottiPerCamera.computeIfAbsent(camera, k -> new ArrayList<>()).add(notte);
            }
        }

        Map<Long, List<Periodo>> risultato = new HashMap<>();
        nottiPerCamera.forEach((camera, notti) -> risultato.put(camera, compatta(notti)));
        return risultato;
    }

    /**
     * Quali camere risultano occupate in una notte.
     *
     * <p>Due passaggi, e l'ordine e' la regola: prima si prendono le <b>fissate</b>,
     * cioe' le occupazioni che una camera ce l'hanno gia'; poi si distribuiscono le
     * altre sulle camere rimaste, seguendo l'ordine ricevuto.
     */
    private static Set<Long> occupateInQuellaNotte(List<Long> camere,
                                                   List<UnitaOccupata> occupazioni,
                                                   LocalDate notte) {
        Set<Long> occupate = new HashSet<>();
        int daDistribuire = 0;

        for (UnitaOccupata unita : occupazioni) {
            if (!unita.copre(notte)) {
                continue;
            }
            if (unita.cameraFissata() != null) {
                occupate.add(unita.cameraFissata());
            } else {
                daDistribuire++;
            }
        }

        for (Long camera : camere) {
            if (daDistribuire == 0) {
                break;
            }
            if (occupate.add(camera)) {
                daDistribuire--;
            }
        }
        // Se daDistribuire e' ancora positivo siamo in overbooking: le camere sono finite
        // e tutte risultano occupate, che e' la risposta giusta da dare a un canale.
        return occupate;
    }

    /**
     * Trasforma un elenco di notti consecutive in periodi.
     *
     * <p>Le notti arrivano gia' in ordine crescente perche' il ciclo di
     * {@link #distribuisci} scorre l'orizzonte in avanti: se un giorno non fosse piu'
     * vero, questo metodo produrrebbe periodi sballati senza accorgersene, ed e' il
     * motivo per cui quella garanzia e' scritta qui invece che lasciata implicita.
     *
     * <p>La data di fine e' <b>esclusa</b>, come in tutto il progetto e come vuole iCal:
     * tre notti dal 3 danno un periodo dal 3 al 6.
     */
    private static List<Periodo> compatta(List<LocalDate> notti) {
        List<Periodo> periodi = new ArrayList<>();
        LocalDate inizio = notti.get(0);
        LocalDate precedente = inizio;

        for (LocalDate notte : notti.subList(1, notti.size())) {
            if (!notte.equals(precedente.plusDays(1))) {
                periodi.add(new Periodo(inizio, precedente.plusDays(1)));
                inizio = notte;
            }
            precedente = notte;
        }
        periodi.add(new Periodo(inizio, precedente.plusDays(1)));
        return periodi;
    }

    /**
     * Una unita' occupata in un periodo, con la camera se si sa gia' quale.
     *
     * @param cameraFissata la camera, oppure {@code null} se l'occupazione vale per una
     *                      unita' qualunque della tipologia
     */
    public record UnitaOccupata(LocalDate inizio, LocalDate fine, Long cameraFissata) {

        /** Se copre quella notte. Fine esclusa, come ovunque nel progetto. */
        public boolean copre(LocalDate notte) {
            return !notte.isBefore(inizio) && notte.isBefore(fine);
        }
    }

    /** Un periodo occupato, con la fine esclusa. */
    public record Periodo(LocalDate inizio, LocalDate fine) {
    }
}
