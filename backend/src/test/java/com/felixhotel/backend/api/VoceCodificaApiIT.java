package com.felixhotel.backend.api;

import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.dto.VoceCodifica;
import com.felixhotel.backend.support.CreatoreStaff;
import com.felixhotel.backend.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione delle tabelle di codifica.
 *
 * <p><b>L'isolamento e' lo stesso problema delle aliquote, e la soluzione e'
 * diversa</b>: anche qui la risorsa e' unica per l'installazione, quindi non c'e'
 * un id di padre con cui separare i test. Ma qui c'e' qualcosa che le aliquote non
 * avevano — <b>quattro famiglie indipendenti</b> — e ogni test ne usa una sua.
 * Dove le famiglie non bastano (i test della lettura e quelli dell'import
 * vogliono tutti e due i COMUNE) l'ordine non conta comunque, perche' <b>l'import
 * sostituisce l'elenco intero</b>: qualunque test parta, trova la famiglia come se
 * l'e' scritta lui.
 *
 * <p>E' una proprieta' che vale la pena notare perche' non capita spesso: la
 * decisione presa per una ragione di dominio — sostituire e non fondere, perche' i
 * comuni si fondono — regala l'isolamento dei test come effetto.
 */
@DisplayName("API delle codifiche ministeriali")
class VoceCodificaApiIT extends IntegrationTestBase {

    private static final String CODIFICHE = "/api/codifiche";

    @Autowired
    private CreatoreStaff creatoreStaff;

    @Nested
    @DisplayName("PUT /api/codifiche/{tipo}")
    class Importa {

        @Test
        @DisplayName("l'ADMIN importa un elenco e lo ritrova")
        void importa_daAdmin_risponde200() throws Exception {
            // given
            String admin = tokenAdmin();

            // when
            mockMvc.perform(put(CODIFICHE + "/TIPO_ALLOGGIATO")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(List.of(
                                    voce("16", "Ospite singolo"),
                                    voce("17", "Capofamiglia"),
                                    voce("18", "Capogruppo")))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("3 voci")))
                    .andExpect(jsonPath("$.data").doesNotExist());

            // then
            mockMvc.perform(get(CODIFICHE + "/TIPO_ALLOGGIATO")
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.totalElements").value(3))
                    .andExpect(jsonPath("$.data[0].descrizione").value("Capofamiglia"));
        }

        @Test
        @DisplayName("un secondo import sostituisce il primo invece di aggiungersi")
        void importa_dueVolte_sostituisce() throws Exception {
            // given: una famiglia con tre voci
            String admin = tokenAdmin();
            importa(admin, "STATO", List.of(
                    voce("100", "FRANCIA"), voce("200", "GERMANIA"), voce("300", "SPAGNA")));

            // when: la versione nuova ne ha due, e una c'era gia'
            mockMvc.perform(put(CODIFICHE + "/STATO")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(List.of(voce("100", "FRANCIA"), voce("400", "PORTOGALLO")))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("2 voci al posto delle 3")));

            // then: e' il test che vale davvero. Un merge avrebbe lasciato GERMANIA e
            // SPAGNA in tabella, cioe' due codici che il Ministero ha tolto e che nessuno
            // si sarebbe accorto di avere. E prova anche che il flush fra la delete e gli
            // insert funzioni: FRANCIA c'era e c'e' ancora, quindi l'indice unico avrebbe
            // potuto scattare
            mockMvc.perform(get(CODIFICHE + "/STATO")
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.totalElements").value(2));
        }

        @Test
        @DisplayName("un elenco vuoto svuota la famiglia")
        void importa_conElencoVuoto_svuota() throws Exception {
            // given
            String admin = tokenAdmin();
            importa(admin, "TIPO_DOCUMENTO", List.of(voce("IDENT", "CARTA DI IDENTITA'")));

            // when: e' il modo di annullare un import sbagliato
            mockMvc.perform(put(CODIFICHE + "/TIPO_DOCUMENTO")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(List.of())))
                    .andExpect(status().isOk());

            // then
            mockMvc.perform(get(CODIFICHE + "/TIPO_DOCUMENTO")
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.totalElements").value(0));
        }

        @Test
        @DisplayName("lo stesso codice due volte nell'elenco risponde 400 e dice quale")
        void importa_conCodiciDoppi_risponde400() throws Exception {
            mockMvc.perform(put(CODIFICHE + "/STATO")
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(List.of(voce("100", "FRANCIA"), voce("100", "FRANCIA")))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("100")));
        }

        @Test
        @DisplayName("una voce senza codice risponde 400: lo pretende lo spec")
        void importa_senzaCodice_risponde400() throws Exception {
            mockMvc.perform(put(CODIFICHE + "/STATO")
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[{\"descrizione\":\"FRANCIA\"}]"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("una famiglia che non esiste risponde 400, non 500")
        void importa_conTipoInesistente_risponde400() throws Exception {
            // given / when / then: e' la regola 21 al lavoro. Lo schema del parametro e'
            // referenziato e non scritto in linea, quindi il generatore produce un enum:
            // il valore sbagliato si ferma al bordo invece di rompersi nel Service
            mockMvc.perform(put(CODIFICHE + "/REGIONI")
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(List.of(voce("100", "LAZIO")))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/codifiche/{tipo}")
    class Elenca {

        @Test
        @DisplayName("il filtro cerca dentro la descrizione, non solo all'inizio")
        void elenca_conFiltroInMezzo_trova() throws Exception {
            // given
            String admin = tokenAdmin();
            importa(admin, "COMUNE", List.of(
                    voceConProvincia("035033", "REGGIO NELL'EMILIA", "RE"),
                    voceConProvincia("080063", "REGGIO DI CALABRIA", "RC"),
                    voceConProvincia("058091", "ROMA", "RM")));

            // when / then: chi si ricorda un pezzo del nome deve trovarlo. Se il filtro
            // fosse uno startsWith, "emilia" non troverebbe niente
            mockMvc.perform(get(CODIFICHE + "/COMUNE")
                            .header("Authorization", "Bearer " + admin)
                            .param("filtro", "emilia"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.totalElements").value(1))
                    .andExpect(jsonPath("$.data[0].provincia").value("RE"));
        }

        @Test
        @DisplayName("il filtro ignora le maiuscole")
        void elenca_conFiltroInMaiuscolo_trova() throws Exception {
            // given
            String admin = tokenAdmin();
            importa(admin, "COMUNE", List.of(voceConProvincia("058091", "ROMA", "RM")));

            // when / then: il codice distingue le maiuscole, la descrizione no — perche'
            // il codice lo emette un'autorita' e la descrizione la cerca una persona
            mockMvc.perform(get(CODIFICHE + "/COMUNE")
                            .header("Authorization", "Bearer " + admin)
                            .param("filtro", "RoMa"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.totalElements").value(1));
        }

        @Test
        @DisplayName("l'ordine e' alfabetico per descrizione, non per codice")
        void elenca_ordinaPerDescrizione() throws Exception {
            // given: i codici sono in ordine inverso rispetto ai nomi
            String admin = tokenAdmin();
            importa(admin, "STATO", List.of(
                    voce("300", "ALBANIA"), voce("200", "BELGIO"), voce("100", "CROAZIA")));

            // when / then: e' una tendina, e chi la guarda cerca un nome
            mockMvc.perform(get(CODIFICHE + "/STATO")
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].descrizione").value("ALBANIA"))
                    .andExpect(jsonPath("$.data[2].descrizione").value("CROAZIA"));
        }

        @Test
        @DisplayName("le famiglie non si mescolano: lo stesso codice puo' stare in due")
        void elenca_conCodiceInDueFamiglie_leTieneSeparate() throws Exception {
            // given: il codice "100" in due famiglie diverse
            String admin = tokenAdmin();
            importa(admin, "STATO", List.of(voce("100", "FRANCIA")));
            importa(admin, "TIPO_ALLOGGIATO", List.of(voce("100", "Ospite singolo")));

            // when / then: l'indice unico e' sulla coppia (tipo, codice), quindi due
            // elenchi che non si parlano possono usare lo stesso numero
            mockMvc.perform(get(CODIFICHE + "/STATO")
                            .header("Authorization", "Bearer " + admin)
                            .param("filtro", "FRANCIA"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.totalElements").value(1));
        }
    }

    @Nested
    @DisplayName("Permessi")
    class Permessi {

        @Test
        @DisplayName("lo STAFF legge ma non importa")
        void codifiche_daStaff_leggeMaNonImporta() throws Exception {
            // given / when / then: serve a chi compila una schedina al banco, ma
            // trascrivere le tabelle del Ministero e' un'altra cosa
            String staff = tokenStaff();

            mockMvc.perform(get(CODIFICHE + "/COMUNE")
                            .header("Authorization", "Bearer " + staff))
                    .andExpect(status().isOk());

            mockMvc.perform(put(CODIFICHE + "/COMUNE")
                            .header("Authorization", "Bearer " + staff)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(List.of(voce("001", "ROMA")))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("un cliente non vede nemmeno l'elenco")
        void codifiche_daCliente_risponde403() throws Exception {
            RegisterRequest cliente = dati.registerRequest();
            auth.registraAccount(cliente);

            mockMvc.perform(get(CODIFICHE + "/COMUNE")
                            .header("Authorization", "Bearer " + auth.ottieniToken(cliente.getEmail())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("senza token risponde 401, non 403")
        void codifiche_senzaToken_risponde401() throws Exception {
            mockMvc.perform(get(CODIFICHE + "/COMUNE"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ---- infrastruttura ----------------------------------------------------------

    private String tokenAdmin() throws Exception {
        String email = dati.emailUnivoca();
        creatoreStaff.creaAdmin(email);
        return auth.ottieniToken(email);
    }

    private String tokenStaff() throws Exception {
        String email = dati.emailUnivoca();
        creatoreStaff.creaStaff(email);
        return auth.ottieniToken(email);
    }

    private VoceCodifica voce(String codice, String descrizione) {
        return new VoceCodifica().codice(codice).descrizione(descrizione);
    }

    private VoceCodifica voceConProvincia(String codice, String descrizione, String provincia) {
        return voce(codice, descrizione).provincia(provincia);
    }

    private void importa(String token, String tipo, List<VoceCodifica> voci) throws Exception {
        mockMvc.perform(put(CODIFICHE + "/" + tipo)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(voci)))
                .andExpect(status().isOk());
    }
}
