package com.example.petclinic.repository;

import java.util.Optional;

public interface OwnerRepository {
    Optional<Owner> findById(long ownerId);
}
