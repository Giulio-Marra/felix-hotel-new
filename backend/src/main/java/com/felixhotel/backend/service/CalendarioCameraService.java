package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;

/**
 * Il calendario iCal di una camera, e l'indirizzo da cui i canali esterni lo leggono.
 *
 * <p><b>Due operazioni molto diverse fra loro</b>, e vale la pena vederlo dalla firma:
 * {@link #feed} restituisce una <b>stringa</b> e non la busta del progetto, perche' deve
 * restituire un file che Booking sa leggere; {@link #generaIndirizzo} restituisce la
 * busta come tutto il resto. Stanno nella stessa interfaccia perche' parlano della stessa
 * cosa, non perche' si somiglino.
 */
public interface CalendarioCameraService {

    /**
     * Il calendario di una camera, in formato iCalendar.
     *
     * <p><b>Non restituisce la busta standard</b>, ed e' l'unica eccezione del progetto
     * alla regola 10. Non e' una scorciatoia: qui il formato non lo scegliamo noi — lo
     * pretende chi legge, e un JSON con dentro un iCal non lo saprebbe usare nessuno.
     *
     * @param token l'indirizzo segreto della camera. Un token che non corrisponde a
     *              niente e' 404: chi chiama non ha sbagliato permessi, ha sbagliato
     *              indirizzo
     */
    String feed(String token);

    /**
     * Genera — o rigenera — l'indirizzo del calendario di una camera.
     *
     * <p><b>Rigenerarlo invalida il precedente.</b> E' tutto il motivo per cui l'indirizzo
     * e' un token salvato e non un valore derivato dall'id: se finisce dove non doveva, si
     * cambia. Il canale che lo stava leggendo va riconfigurato — che e' quel che si vuole
     * quando si revoca un accesso, ma va saputo prima.
     */
    ApiBaseResponse generaIndirizzo(Long cameraId);

    /**
     * Toglie l'indirizzo del calendario: da quel momento quel feed non esiste piu'.
     *
     * <p><b>Non e' rigenerare.</b> Rigenerare invalida il vecchio link ma ne crea uno
     * nuovo altrettanto valido, e serve a chi ha perso il controllo di un indirizzo
     * volendo continuare a pubblicare. Questo serve a chi ha pubblicato una camera per
     * sbaglio, o l'ha tolta dalla vendita su quel canale: dopo, di indirizzi validi non ce
     * n'e' nessuno.
     */
    ApiBaseResponse spubblica(Long cameraId);
}
