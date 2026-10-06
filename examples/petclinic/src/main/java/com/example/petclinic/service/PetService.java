package com.example.petclinic.service;

import com.example.petclinic.repository.Owner;
import com.example.petclinic.repository.OwnerRepository;
import java.util.Optional;

public class PetService {
    private final OwnerRepository ownerRepository;
    public PetService(OwnerRepository ownerRepository) { this.ownerRepository = ownerRepository; }

    public Pet createPet(long ownerId, String name) {
        Owner owner = ownerRepository.findById(ownerId).get();
        return new Pet(name, owner);
    }
}
