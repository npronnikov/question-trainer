package ru.questionhacker.trainer.practice;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PracticeAdministrationService {

    private final PracticeRepository practice;

    public PracticeAdministrationService(PracticeRepository practice) {
        this.practice = practice;
    }

    @Transactional
    public int clearAllCycles() {
        return practice.deleteAllCycles();
    }
}
