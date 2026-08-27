package com.felixhotel.backend.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Adapter Spring Security su {@code Utente}/{@code Staff}: le due entita'
 * condividono email + passwordHash + ruolo per il login, ma sono classi JPA
 * distinte e nessuna delle due implementa direttamente UserDetails. Vedi
 * {@link CustomUserDetailsService}, che costruisce questo oggetto a partire
 * dall'una o dall'altra.
 *
 * <p><b>{@code userId} e {@code tipo} si leggono insieme, mai da soli.</b> Le
 * due tabelle hanno sequenze indipendenti, quindi l'id da solo non identifica
 * nessuno: e' {@link TipoAccount} a dire su quale delle due vale. Chi ha
 * bisogno dell'account vero fa il controllo sul tipo e poi una lettura per id,
 * senza passare dall'email.
 */
public class AppUserPrincipal implements UserDetails {

    private final TipoAccount tipo;
    private final Long userId;
    private final String email;
    private final String passwordHash;
    private final String nome;
    private final String cognome;
    private final String ruoloNome;
    private final boolean attivo;

    public AppUserPrincipal(TipoAccount tipo, Long userId, String email, String passwordHash, String nome,
                             String cognome, String ruoloNome, boolean attivo) {
        this.tipo = tipo;
        this.userId = userId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.nome = nome;
        this.cognome = cognome;
        this.ruoloNome = ruoloNome;
        this.attivo = attivo;
    }

    /** Su quale tabella vale {@link #getUserId()}. Non e' il ruolo: vedi {@link TipoAccount}. */
    public TipoAccount getTipo() {
        return tipo;
    }

    public Long getUserId() {
        return userId;
    }

    public String getNome() {
        return nome;
    }

    public String getCognome() {
        return cognome;
    }

    public String getRuoloNome() {
        return ruoloNome;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + ruoloNome));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return attivo;
    }
}
