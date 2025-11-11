package com.hyperxray.an.telemetry

import kotlin.random.Random

/**
 * Mock test for RealityBandit with LinUCB algorithm.
 * Tests with 3 sample arms and random rewards.
 */
fun main() {
    println("=== RealityBandit Mock Test ===")
    println()
    
    try {
        // Create 3 sample Reality arms
        val arm1 = RealityArm.create(
            RealityContext(
                address = "server1.example.com",
                port = 443,
                serverName = "cloudflare.com",
                shortId = "abc123",
                publicKey = "dGVzdF9wdWJsaWNfa2V5XzEyMzQ1Njc4OTA=",
                destination = "www.google.com:443",
                configId = "config-1"
            )
        )
        
        val arm2 = RealityArm.create(
            RealityContext(
                address = "server2.example.com",
                port = 8443,
                serverName = "microsoft.com",
                shortId = "def456",
                publicKey = "YW5vdGhlcl9wdWJsaWNfa2V5XzE5ODc2NTQzMjE=",
                destination = "www.microsoft.com:443",
                configId = "config-2"
            )
        )
        
        val arm3 = RealityArm.create(
            RealityContext(
                address = "server3.example.com",
                port = 443,
                serverName = "github.com",
                shortId = "ghi789",
                publicKey = "dGhpcmRfcHVibGljX2tleV8xMTIyMzM0NDU1",
                destination = "www.github.com:443",
                configId = "config-3"
            )
        )
        
        println("Created 3 sample arms:")
        println("  - Arm 1: ${arm1.armId}")
        println("  - Arm 2: ${arm2.armId}")
        println("  - Arm 3: ${arm3.armId}")
        println()
        
        // Initialize bandit
        val bandit = RealityBandit()
        bandit.addArms(listOf(arm1, arm2, arm3))
        
        println("Initialized RealityBandit with LinUCB")
        println()
        
        // Run mock test: 20 rounds of selection and update
        val random = Random(42) // Fixed seed for reproducibility
        val numRounds = 20
        
        println("Running $numRounds rounds of selection and update...")
        println()
        
        for (round in 1..numRounds) {
            // Select best arm
            val selectedArm = bandit.select()
            
            // Generate random reward (simulating connection quality)
            // Reward range: -50 to 150 (simulating various connection qualities)
            val reward = random.nextDouble(-50.0, 150.0)
            
            // Update bandit with reward
            bandit.update(selectedArm.armId, reward)
            
            if (round <= 5 || round % 5 == 0) {
                println("Round $round: Selected ${selectedArm.armId}, Reward: ${"%.2f".format(reward)}")
            }
        }
        
        println()
        println("=== Validation ===")
        
        // Validation 1: Check that select() returns RealityArm
        val testArm = bandit.select()
        require(testArm is RealityArm) { "select() must return RealityArm" }
        println("✓ select() returns RealityArm: ${testArm.armId}")
        
        // Validation 2: Check that update() modifies coefficients
        val weightsBefore = bandit.getLearnedWeights(testArm.armId)?.copyOf()
        val testReward = 100.0
        bandit.update(testArm.armId, testReward)
        val weightsAfter = bandit.getLearnedWeights(testArm.armId)?.copyOf()
        
        require(weightsBefore != null && weightsAfter != null) {
            "Weights should not be null"
        }
        
        var weightsChanged = false
        for (i in weightsBefore.indices) {
            if (kotlin.math.abs(weightsBefore[i] - weightsAfter[i]) > 1e-10) {
                weightsChanged = true
                break
            }
        }
        require(weightsChanged) { "update() must modify coefficients" }
        println("✓ update() modifies coefficients")
        
        // Validation 3: Check for NaN/Inf in learned weights
        val allWeights = bandit.getLearnedWeights()
        var hasNaN = false
        var hasInf = false
        
        for ((armId, weights) in allWeights) {
            for (value in weights) {
                if (value.isNaN()) {
                    hasNaN = true
                    println("  ✗ NaN found in weights for $armId")
                }
                if (value.isInfinite()) {
                    hasInf = true
                    println("  ✗ Inf found in weights for $armId")
                }
            }
        }
        
        require(!hasNaN) { "No NaN values allowed in learned weights" }
        require(!hasInf) { "No Inf values allowed in learned weights" }
        println("✓ No NaN/Inf in learned weights (A^-1 validation passed)")
        
        println()
        println("=== Learned Weights (Theta Vectors) ===")
        for ((armId, weights) in allWeights) {
            println("Arm: $armId")
            println("  Theta: [${weights.joinToString(", ") { "%.6f".format(it) }}]")
            val arm = bandit.getArm(armId)
            if (arm != null) {
                println("  Pulls: ${arm.pullCount}, Avg Reward: ${"%.2f".format(arm.averageReward)}")
            }
            println()
        }
        
        println("=== Test Summary ===")
        println("Total selections: ${bandit.getTotalSelections()}")
        println("All arms initialized: ${bandit.getAllArms().size == 3}")
        println()
        
        println("✅ Bandit compiled successfully")
        println()
        
        // Print remaining TODOs
        println("=== Remaining TODOs ===")
        println("  - [ ] Integrate with telemetry collection system")
        println("  - [ ] Add persistence for learned weights")
        println("  - [ ] Implement context feature engineering improvements")
        println("  - [ ] Add arm removal/expiration logic")
        println("  - [ ] Performance optimization for large arm sets")
        println("  - [ ] Add confidence interval visualization")
        println()
        
        println("NEXT-STAGE: Safety & Control")
        
    } catch (e: Exception) {
        println("❌ Test failed: ${e.message}")
        e.printStackTrace()
        throw e
    }
}

