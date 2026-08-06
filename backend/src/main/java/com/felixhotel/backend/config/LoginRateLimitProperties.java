package com.felixhotel.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Parametri della protezione contro il brute force sul login (prefisso
 * {@code felix.security.login} in {@code application.properties}).
 *
 * <p><b>Il meccanismo e' un ritardo progressivo, non un blocco.</b> Superati i
 * primi tentativi falliti "liberi", fra un tentativo e il successivo si impone
 * un'attesa che raddoppia ogni volta, fino a un tetto. Chi sbaglia la propria
 * password due o tre volte non se ne accorge; chi le prova a macchina si ritrova
 * a poter fare una manciata di tentativi al minuto invece di migliaia al secondo.
 *
 * <p>Perche' non un blocco dell'account dopo N tentativi, che sarebbe piu'
 * semplice: perche' e' un'arma a doppio taglio. Chiunque conosca l'email di un
 * ADMIN potrebbe chiuderlo fuori a comando sbagliando la password apposta —
 * la protezione dal brute force diventerebbe un modo per fare disservizio. Il
 * ritardo progressivo rende l'attacco impraticabile senza togliere a nessuno
 * l'accesso al proprio account.
 *
 * <p>Sono configurabili e non costanti nel codice perche' la taratura giusta
 * dipende dall'ambiente: nei test conviene stringerla per poterla verificare in
 * fretta, in produzione va misurata sul traffico reale. Non sono segreti, quindi
 * i valori stanno direttamente nel file e non fra le variabili d'ambiente
 * (regola 9).
 *
 * @param tentativiLiberiEmail fallimenti tollerati sulla stessa email prima che
 *                             il ritardo cominci ad applicarsi. Tenuto basso: e'
 *                             la difesa dell'account preso di mira. Sul conto
 *                             esatto — il tentativo si conta dopo averlo lasciato
 *                             passare, quindi ne passa sempre uno in piu' della
 *                             soglia — vedi
 *                             {@link RegistrationRateLimitProperties}, dove il
 *                             ragionamento e' scritto per esteso: il meccanismo
 *                             e' lo stesso per entrambi.
 * @param tentativiLiberiIp    fallimenti tollerati dallo stesso indirizzo IP
 *                             prima del ritardo. Piu' alto del precedente perche'
 *                             dietro un IP possono esserci molte persone
 *                             legittime (rete aziendale, NAT); serve contro chi
 *                             prova una password comune su tante email diverse.
 * @param ritardoIniziale      attesa imposta al primo tentativo oltre la soglia.
 *                             Raddoppia ad ogni fallimento successivo.
 * @param ritardoMassimo       tetto del raddoppio. Senza, dopo qualche decina di
 *                             tentativi l'attesa diventerebbe di giorni: un
 *                             blocco di fatto, cioe' esattamente cio' che si
 *                             vuole evitare.
 * @param finestra             inattivita' dopo la quale il conteggio si azzera da
 *                             solo. Chi sbaglia due password oggi e due fra un
 *                             mese non e' un attacco e riparte da zero.
 */
@ConfigurationProperties(prefix = "felix.security.login")
public record LoginRateLimitProperties(

        @DefaultValue("4") int tentativiLiberiEmail,
        @DefaultValue("20") int tentativiLiberiIp,
        @DefaultValue("1s") Duration ritardoIniziale,
        @DefaultValue("60s") Duration ritardoMassimo,
        @DefaultValue("15m") Duration finestra) {
}
