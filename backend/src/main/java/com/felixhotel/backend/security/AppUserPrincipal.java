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
 */
public class AppUserPrincipal implements UserDetails {

    private final Long userId;
    private final String email;
    private final String passwordHash;
    private final String nome;
    private final String cognome;
    private final String ruoloNome;
    private final boolean attivo;

    public AppUserPrincipal(Long userId, String email, String passwordHash, String nome, String cognome,
                             String ruoloNome, boolean attivo) {
        this.userId = userId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.nome = nome;
        this.cognome = cognome;
        this.ruoloNome = ruoloNome;
        this.attivo = attivo;
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
