package com.cau.capstone8.backend.user;

import jakarta.persistence.*;

@Entity
@Table(name = "app_user")
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "display_name", nullable = false, length = 120) private String displayName;

    protected AppUser() {}

    public Long getId() { return id; }
    public String getDisplayName() { return displayName; }
}
