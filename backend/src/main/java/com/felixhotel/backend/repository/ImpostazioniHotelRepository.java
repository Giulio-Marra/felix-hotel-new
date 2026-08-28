package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.ImpostazioniHotel;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Accesso all'anagrafica della struttura.
 *
 * <p><b>Non dichiara nessun metodo suo</b>, ed e' l'unico repository del
 * progetto a non averne: la tabella contiene una riga sola, con id noto
 * ({@link ImpostazioniHotel#ID_RIGA_UNICA}), quindi {@code findById} e
 * {@code save} ereditati bastano. Un {@code trovaLeImpostazioni()} sarebbe solo
 * un nome diverso per la stessa chiamata, e nasconderebbe da quale riga i dati
 * arrivano.
 *
 * <p>Non c'e' nemmeno un modo di crearne una: la riga nasce dalla migration
 * {@code V8__identita_struttura.sql} e il vincolo {@code CHECK (id = 1)}
 * impedisce che ne compaia una seconda. {@code save} qui vuol dire sempre
 * "riscrivi quella che c'e'".
 */
public interface ImpostazioniHotelRepository extends JpaRepository<ImpostazioniHotel, Long> {
}
