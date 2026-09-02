package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.TipoTokenEmail;
import com.felixhotel.backend.entity.TokenEmail;
import com.felixhotel.backend.security.TipoAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Accesso ai token mandati per email.
 *
 * <p>Tre operazioni, che sono i tre momenti della vita di un token: lo si <b>cerca</b>
 * quando qualcuno arriva con un link in mano, si <b>tolgono i precedenti</b> quando se
 * ne emette uno nuovo, e si <b>ripuliscono gli scaduti</b> ogni tanto.
 */
public interface TokenEmailRepository extends JpaRepository<TokenEmail, Long> {

    /**
     * Il token con questa impronta.
     *
     * <p><b>Si cerca per impronta e non per token</b>, perche' in tabella il token non
     * c'e': chi chiama calcola l'SHA-256 di quel che ha ricevuto e cerca quello. E'
     * anche il motivo per cui questa query e' esatta e non un confronto uno per uno —
     * l'indice unico del V14 la serve per intero, quindi verificare un link costa una
     * lettura sola per quanti token ci siano in tabella.
     *
     * <p>Restituisce anche i token <b>scaduti o gia' usati</b>, di proposito: la
     * differenza fra <i>"questo link non esiste"</i> e <i>"questo link non vale piu'"</i>
     * la decide chi chiama, e per deciderla deve poterlo vedere. Filtrarla qui
     * renderebbe i due casi indistinguibili proprio dove servono distinti.
     */
    Optional<TokenEmail> findByTokenHash(String tokenHash);

    /**
     * Cancella i token ancora pendenti dello stesso tipo per lo stesso destinatario.
     *
     * <p>Serve quando se ne emette uno nuovo, ed e' una decisione e non una pulizia:
     * chi chiede due volte il reset della password si aspetta che valga <b>l'ultimo
     * link ricevuto</b>. Lasciando validi tutti e due, il piu' vecchio resterebbe una
     * porta aperta per un'ora dopo che il suo proprietario ha gia' fatto altro — e
     * soprattutto chi legge la propria posta non ha modo di sapere quale dei due sia
     * quello buono.
     *
     * <p><b>Si cancella e non si marca come usato</b>: {@code usatoIl} vuol dire "e'
     * stato consumato", e scriverlo su un token che nessuno ha mai aperto sarebbe una
     * bugia in una colonna che serve a rispondere <i>quando</i>.
     *
     * <p>Tocca solo i pendenti: un token gia' consumato resta dov'e', perche' e' cio'
     * che permette di rispondere "questo link e' gia' stato usato" a chi ci riclicca.
     *
     * @return quanti ne sono stati tolti
     */
    @Modifying
    int deleteByTipoAndTipoAccountAndSoggettoIdAndUsatoIlIsNull(
            TipoTokenEmail tipo, TipoAccount tipoAccount, Long soggettoId);

    /**
     * Cancella i token scaduti, usati o no.
     *
     * <p>La chiama un {@code @Scheduled}, come gia' fanno i contatori dei tentativi di
     * login e di registrazione: senza, questa tabella cresce per sempre di una riga per
     * ogni registrazione e ogni reset, e nessuna di quelle righe serve piu' a niente.
     *
     * <p><b>La soglia e' la scadenza e non l'uso</b>, ed e' voluto: un token consumato
     * ma non ancora scaduto resta in tabella, perche' e' l'unica cosa che sa rispondere
     * "questo link e' gia' stato usato" a chi lo riapre — cosa che capita davvero,
     * visto che i client di posta pre-caricano i link.
     *
     * @return quante righe sono state tolte, che il chiamante mette nel log
     */
    @Modifying
    int deleteByScadenzaBefore(LocalDateTime soglia);
}
