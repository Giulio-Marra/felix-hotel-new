package com.felixhotel.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Le due cose che servono a mandare un'email e che cambiano da un'installazione
 * all'altra (prefisso {@code felix.email} in {@code application.properties}).
 *
 * <p><b>Qui non ci sono credenziali</b>, ed e' voluto: host, porta, utente e password
 * dell'SMTP li configura Spring sotto {@code spring.mail}, e la password arriva da una
 * variabile d'ambiente come tutti gli altri segreti (regola 9). Queste due invece non
 * sono segrete — sono l'identita' di chi scrive e l'indirizzo a cui i link riportano —
 * quindi stanno nel file di configurazione, dove si leggono.
 *
 * @param mittente indirizzo che comparira' come mittente. Deve appartenere a un dominio
 *                 verificato presso il fornitore, altrimenti i messaggi partono e non
 *                 arrivano: e' la cosa che serve prima della messa in esercizio, e non
 *                 blocca lo sviluppo perche' Mailpit accetta qualunque mittente.
 * @param baseUrl  indirizzo pubblico da cui si raggiunge l'applicazione, usato per
 *                 costruire i link dentro le email. <b>Non e' l'URL del backend ma
 *                 quello del frontend</b>: chi riceve un invito deve atterrare su una
 *                 pagina che gli chiede la password, non su una risposta JSON. Finche'
 *                 il frontend non c'e' punta al backend, ed e' scritto qui perche' il
 *                 giorno in cui nasce sia una riga di configurazione e non una caccia
 *                 al posto dove i link vengono composti.
 */
@ConfigurationProperties(prefix = "felix.email")
public record EmailProperties(

        @DefaultValue("felix-hotel@example.com") String mittente,

        @DefaultValue("http://localhost:8080") String baseUrl) {
}
