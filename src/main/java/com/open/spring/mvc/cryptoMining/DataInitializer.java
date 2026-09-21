package com.open.spring.mvc.cryptoMining;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@ConditionalOnProperty(name = "app.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class DataInitializer implements CommandLineRunner {
    @Autowired
    private GPURepository gpuRepository;
    
    @Autowired
    private EnergyRepository energyRepository;

    @Autowired
    private DataSource dataSource;

    @Override
    public void run(String... args) {
        // Initialize GPUs if none exist
        if (gpuRepository.count() == 0) {
            initializeGPUs();
        }

        // Keep startup alive even if local schema cannot write to energy yet.
        try {
            System.out.println("Initializing energy plans...");
            initializeEnergyPlans();
            System.out.println("Energy plans initialized successfully");
        } catch (Exception e) {
            System.err.println("Skipping energy plan initialization due to database schema mismatch: " + e.getMessage());
        }
    }

    private void initializeGPUs() {
       // Budget GPUs ($10000-20000)
       createGPU("NVIDIA GeForce GT 1030", 1.55, 30, 65, 10000, "Budget GPUs ($10000-20000)");
       createGPU("NVIDIA GeForce GTX 1050", 14, 75, 67, 10000, "Budget GPUs ($10000-20000)");
       createGPU("AMD RX 570 8GB", 28, 120, 70, 15000, "Budget GPUs ($10000-20000)");
       createGPU("NVIDIA GeForce GTX 1060 6GB", 22, 120, 68, 20000, "Budget GPUs ($10000-20000)");

       // Mid-Range GPUs ($20000-50000)
       createGPU("NVIDIA GeForce GTX 1660 SUPER", 31, 125, 69, 25000, "Mid-Range GPUs ($20000-50000)");
       createGPU("AMD RX 5600 XT", 40, 150, 71, 30000, "Mid-Range GPUs ($20000-50000)");
       createGPU("NVIDIA RTX 2060", 32, 160, 70, 35000, "Mid-Range GPUs ($20000-50000)");
       createGPU("NVIDIA RTX 2070", 42, 175, 71, 45000, "Mid-Range GPUs ($20000-50000)");

       // High-End GPUs ($50000-100000)
       createGPU("NVIDIA RTX 3060 Ti", 60, 200, 70, 55000, "High-End GPUs ($50000-100000)");
       createGPU("NVIDIA RTX 3070", 62, 220, 71, 65000, "High-End GPUs ($50000-100000)");
       createGPU("NVIDIA RTX 3080", 64, 300, 73, 80000, "High-End GPUs ($50000-100000)");
       createGPU("NVIDIA RTX 3090", 98, 320, 72, 95000, "High-End GPUs ($50000-100000)");

       // Premium GPUs ($100000+)
       createGPU("NVIDIA RTX 4070", 100, 285, 71, 120000, "Premium GPUs ($100000+)");
       createGPU("AMD RX 7900 XTX", 110, 355, 73, 150000, "Premium GPUs ($100000+)");
       createGPU("NVIDIA RTX 4080", 130, 320, 73, 180000, "Premium GPUs ($100000+)");
       createGPU("NVIDIA RTX 4090", 140, 450, 75, 200000, "Premium GPUs ($100000+)");
   }

    private void createGPU(String name, double hashRate, int power, int temp, double price, String category) {
        GPU gpu = new GPU();
        gpu.setName(name);
        gpu.setHashRate(hashRate);
        gpu.setPowerConsumption(power);
        gpu.setTemp(temp);
        gpu.setPrice(price);
        gpu.setCategory(category);
        gpuRepository.save(gpu);
    }

    private void initializeEnergyPlans() {
        createEnergyPlan("Tesla Energy", 0.12);
        createEnergyPlan("Duke Energy", 0.15);
        createEnergyPlan("Pacific Gas and Electric", 0.18);
        createEnergyPlan("NextEra Energy", 0.21);
        createEnergyPlan("Southern Company", 0.24);
    }

    private void createEnergyPlan(String supplierName, double EEM) {
        if (!energyRepository.findBySupplierName(supplierName).isEmpty()) {
            return;
        }

        Energy energy = new Energy(supplierName, EEM);
        energyRepository.save(energy);
    }

}
