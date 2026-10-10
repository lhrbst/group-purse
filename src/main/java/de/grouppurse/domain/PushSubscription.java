package de.grouppurse.domain;

import jakarta.persistence.Entity;

import java.util.UUID;

@Entity
public class PushSubscription extends TenantEntity {
    public UUID personId;
    public String endpoint;
    public String p256dh;
    public String auth;
}
