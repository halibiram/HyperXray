package com.hyperxray.an.telemetry

import kotlin.math.sqrt
import kotlin.math.ln

/**
 * LinUCB (Linear Upper Confidence Bound) bandit algorithm implementation.
 * Uses 8-dimensional context vectors and Gauss-Jordan elimination for matrix inversion.
 * 
 * LinUCB maintains per-arm linear models:
 * - A_a: d×d matrix (regularization + context outer products)
 * - b_a: d×1 vector (reward-weighted contexts)
 * - theta_a = A_a^-1 * b_a: learned weight vector
 * 
 * Selection: argmax_a [theta_a^T * x_t + alpha * sqrt(x_t^T * A_a^-1 * x_t)]
 */
class LinUCB(
    /**
     * Dimension of context vectors (fixed at 8)
     */
    val contextDimension: Int = 8,
    
    /**
     * Exploration parameter (alpha) - controls confidence bound width
     * Higher alpha = more exploration
     */
    val alpha: Double = 1.0,
    
    /**
     * Regularization parameter (lambda) - added to diagonal of A matrix
     * Prevents singular matrices and overfitting
     */
    val lambda: Double = 1.0
) {
    /**
     * Per-arm matrices A_a (contextDimension × contextDimension)
     * Key: armId, Value: A matrix as 2D array
     */
    private val matricesA: MutableMap<String, Array<DoubleArray>> = mutableMapOf()
    
    /**
     * Per-arm vectors b_a (contextDimension × 1)
     * Key: armId, Value: b vector as 1D array
     */
    private val vectorsB: MutableMap<String, DoubleArray> = mutableMapOf()
    
    /**
     * Per-arm inverse matrices A_a^-1 (cached for efficiency)
     * Key: armId, Value: A^-1 matrix as 2D array
     */
    private val inverseMatrices: MutableMap<String, Array<DoubleArray>> = mutableMapOf()
    
    /**
     * Per-arm learned weight vectors theta_a = A^-1 * b
     * Key: armId, Value: theta vector as 1D array
     */
    private val thetaVectors: MutableMap<String, DoubleArray> = mutableMapOf()
    
    /**
     * Initialize an arm with identity matrix regularization
     */
    fun initializeArm(armId: String) {
        if (armId in matricesA) {
            return // Already initialized
        }
        
        // Initialize A = lambda * I (identity matrix)
        val matrixA = Array(contextDimension) { i ->
            DoubleArray(contextDimension) { j ->
                if (i == j) lambda else 0.0
            }
        }
        
        // Initialize b = zero vector
        val vectorB = DoubleArray(contextDimension) { 0.0 }
        
        // Initialize A^-1 = (1/lambda) * I
        val matrixAInv = Array(contextDimension) { i ->
            DoubleArray(contextDimension) { j ->
                if (i == j) 1.0 / lambda else 0.0
            }
        }
        
        // Initialize theta = A^-1 * b = zero vector
        val theta = DoubleArray(contextDimension) { 0.0 }
        
        matricesA[armId] = matrixA
        vectorsB[armId] = vectorB
        inverseMatrices[armId] = matrixAInv
        thetaVectors[armId] = theta
    }
    
    /**
     * Compute upper confidence bound for an arm given context
     * UCB = theta^T * x + alpha * sqrt(x^T * A^-1 * x)
     */
    fun computeUCB(armId: String, context: DoubleArray): Double {
        require(context.size == contextDimension) {
            "Context dimension must be $contextDimension, got ${context.size}"
        }
        
        if (armId !in thetaVectors) {
            initializeArm(armId)
        }
        
        val theta = thetaVectors[armId]!!
        val aInv = inverseMatrices[armId]!!
        
        // Compute theta^T * x (dot product)
        var meanReward = 0.0
        for (i in 0 until contextDimension) {
            meanReward += theta[i] * context[i]
        }
        
        // Compute x^T * A^-1 * x (quadratic form)
        var variance = 0.0
        for (i in 0 until contextDimension) {
            var temp = 0.0
            for (j in 0 until contextDimension) {
                temp += aInv[i][j] * context[j]
            }
            variance += context[i] * temp
        }
        
        // UCB = mean + alpha * sqrt(variance)
        val confidenceBound = alpha * sqrt(variance)
        return meanReward + confidenceBound
    }
    
    /**
     * Update arm parameters after observing reward
     * A_a = A_a + x_t * x_t^T
     * b_a = b_a + r_t * x_t
     * Then recompute A^-1 and theta
     */
    fun update(armId: String, context: DoubleArray, reward: Double) {
        require(context.size == contextDimension) {
            "Context dimension must be $contextDimension, got ${context.size}"
        }
        
        if (armId !in matricesA) {
            initializeArm(armId)
        }
        
        val matrixA = matricesA[armId]!!
        val vectorB = vectorsB[armId]!!
        
        // Update A = A + x * x^T (outer product)
        for (i in 0 until contextDimension) {
            for (j in 0 until contextDimension) {
                matrixA[i][j] += context[i] * context[j]
            }
        }
        
        // Update b = b + r * x
        for (i in 0 until contextDimension) {
            vectorB[i] += reward * context[i]
        }
        
        // Recompute A^-1 using Gauss-Jordan elimination
        val matrixAInv = gaussJordanInverse(matrixA)
        
        // Validate: check for NaN/Inf
        validateMatrix(matrixAInv, "A^-1 for arm $armId")
        
        // Recompute theta = A^-1 * b
        val theta = DoubleArray(contextDimension) { 0.0 }
        for (i in 0 until contextDimension) {
            for (j in 0 until contextDimension) {
                theta[i] += matrixAInv[i][j] * vectorB[j]
            }
        }
        
        // Validate theta
        validateVector(theta, "theta for arm $armId")
        
        // Update cached values
        inverseMatrices[armId] = matrixAInv
        thetaVectors[armId] = theta
    }
    
    /**
     * Gauss-Jordan elimination for matrix inversion
     * Returns A^-1 or throws if matrix is singular
     */
    private fun gaussJordanInverse(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val n = matrix.size
        require(n == contextDimension) { "Matrix size must be $contextDimension" }
        
        // Create augmented matrix [A | I]
        val augmented = Array(n) { i ->
            DoubleArray(2 * n) { j ->
                if (j < n) {
                    matrix[i][j]
                } else {
                    if (j - n == i) 1.0 else 0.0
                }
            }
        }
        
        // Forward elimination: make left side upper triangular
        for (i in 0 until n) {
            // Find pivot (largest absolute value in column i, starting from row i)
            var maxRow = i
            var maxVal = kotlin.math.abs(augmented[i][i])
            
            for (k in i + 1 until n) {
                val absVal = kotlin.math.abs(augmented[k][i])
                if (absVal > maxVal) {
                    maxVal = absVal
                    maxRow = k
                }
            }
            
            // Swap rows if needed
            if (maxRow != i) {
                val temp = augmented[i]
                augmented[i] = augmented[maxRow]
                augmented[maxRow] = temp
            }
            
            // Check for singular matrix
            if (kotlin.math.abs(augmented[i][i]) < 1e-10) {
                throw IllegalStateException("Matrix is singular or near-singular (pivot < 1e-10)")
            }
            
            // Eliminate column i in rows below
            for (k in i + 1 until n) {
                val factor = augmented[k][i] / augmented[i][i]
                for (j in i until 2 * n) {
                    augmented[k][j] -= factor * augmented[i][j]
                }
            }
        }
        
        // Backward elimination: make left side identity
        for (i in n - 1 downTo 0) {
            // Normalize row i
            val pivot = augmented[i][i]
            for (j in i until 2 * n) {
                augmented[i][j] /= pivot
            }
            
            // Eliminate column i in rows above
            for (k in i - 1 downTo 0) {
                val factor = augmented[k][i]
                for (j in i until 2 * n) {
                    augmented[k][j] -= factor * augmented[i][j]
                }
            }
        }
        
        // Extract right side (A^-1)
        val inverse = Array(n) { i ->
            DoubleArray(n) { j ->
                augmented[i][n + j]
            }
        }
        
        return inverse
    }
    
    /**
     * Validate matrix has no NaN or Inf values
     */
    private fun validateMatrix(matrix: Array<DoubleArray>, name: String) {
        for (i in matrix.indices) {
            for (j in matrix[i].indices) {
                val value = matrix[i][j]
                if (value.isNaN()) {
                    throw IllegalStateException("NaN found in $name at [$i][$j]")
                }
                if (value.isInfinite()) {
                    throw IllegalStateException("Inf found in $name at [$i][$j]")
                }
            }
        }
    }
    
    /**
     * Validate vector has no NaN or Inf values
     */
    private fun validateVector(vector: DoubleArray, name: String) {
        for (i in vector.indices) {
            val value = vector[i]
            if (value.isNaN()) {
                throw IllegalStateException("NaN found in $name at [$i]")
            }
            if (value.isInfinite()) {
                throw IllegalStateException("Inf found in $name at [$i]")
            }
        }
    }
    
    /**
     * Get learned weight vector (theta) for an arm
     */
    fun getTheta(armId: String): DoubleArray? {
        return thetaVectors[armId]?.copyOf()
    }
    
    /**
     * Get all learned weight vectors
     */
    fun getAllThetas(): Map<String, DoubleArray> {
        return thetaVectors.mapValues { it.value.copyOf() }
    }
    
    /**
     * Check if arm is initialized
     */
    fun isArmInitialized(armId: String): Boolean {
        return armId in matricesA
    }
}

