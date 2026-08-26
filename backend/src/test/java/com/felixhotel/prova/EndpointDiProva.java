package com.felixhotel.prova;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Il posto unico degli endpoint che esistono solo per i test: li dichiara, e li
 * fa esistere <b>soltanto nei contesti che li chiedono</b>.
 *
 * <p><b>Perche' questo package sta fuori da {@code com.felixhotel.backend}.</b>
 * Non e' una stonatura nella struttura, e' il meccanismo. Il component scan
 * parte dal package di {@code BackendApplication} e si ferma li': una classe
 * annotata {@code @RestController} dentro quel sottoalbero finisce in ogni
 * contesto di test, che lo si voglia o no — ed e' cio' che succedeva a
 * {@code SoloAdminTestController}, che stava in {@code backend.support}. Stando
 * fuori, questi controller non li trova nessuno finche' qualcuno non importa
 * questa classe. La regola sui package e' quindi anche la garanzia.
 *
 * <p><b>Perche' importarla e non lasciare che li scansioni.</b> Gli endpoint di
 * prova sono cresciuti da uno a cinque, e tre di loro esistono per far esplodere
 * l'applicazione: una rotta che solleva di proposito una {@code
 * IllegalStateException} non e' qualcosa da avere aperto in ogni contesto per
 * comodita'. Averli dichiarati da un lato solo vuol dire anche che l'elenco di
 * cio' che nei test esiste in piu' rispetto alla produzione sta in un file, e si
 * legge.
 *
 * <p><b>Cosa costa, misurato e non stimato.</b> Un {@code @Import} cambia la
 * chiave del contesto, quindi gli IT che lo usano ne avviano uno secondo — con
 * un secondo container Postgres, perche' il container e' un bean del contesto e
 * non un singleton statico. Misurato il 2026-08-26 su {@code mvnw verify}:
 * 1m17s con un contesto solo, 1m22s con due. <b>Cinque secondi</b>, non il raddoppio
 * che il commento precedente lasciava temere. A quel prezzo la strada pulita si
 * puo' percorrere; se un domani i contesti diventassero tanti, la mossa e'
 * rendere il container un singleton statico, non tornare al component scan.
 *
 * <p>Va importata da <b>tutti</b> gli IT che usano uno qualsiasi di questi
 * endpoint, anche quando ne servirebbe uno solo: cosi' condividono la stessa
 * chiave e il contesto in piu' resta uno. Spezzarla in due configurazioni
 * significherebbe due contesti e due container.
 */
@TestConfiguration(proxyBeanMethods = false)
public class EndpointDiProva {

    @Bean
    EndpointSoloAdmin endpointSoloAdmin() {
        return new EndpointSoloAdmin();
    }

    @Bean
    EndpointCheEsplodono endpointCheEsplodono() {
        return new EndpointCheEsplodono();
    }
}
