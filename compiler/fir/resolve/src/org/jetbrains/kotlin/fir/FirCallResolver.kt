/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir

import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirMemberDeclaration
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccess
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.expressions.impl.FirResolvedQualifierImpl
import org.jetbrains.kotlin.fir.references.FirBackingFieldReferenceImpl
import org.jetbrains.kotlin.fir.references.FirErrorNamedReference
import org.jetbrains.kotlin.fir.references.FirResolvedCallableReferenceImpl
import org.jetbrains.kotlin.fir.references.FirSimpleNamedReference
import org.jetbrains.kotlin.fir.resolve.BodyResolveComponents
import org.jetbrains.kotlin.fir.resolve.ImplicitReceiverStack
import org.jetbrains.kotlin.fir.resolve.calls.*
import org.jetbrains.kotlin.fir.resolve.transformers.*
import org.jetbrains.kotlin.fir.resolve.typeForQualifier
import org.jetbrains.kotlin.fir.resolve.typeFromCallee
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.impl.FirLocalScope
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.ConeKotlinErrorType
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.resolve.calls.results.TypeSpecificityComparator

class FirCallResolver(
    private val transformer: FirBodyResolveTransformer,
    private val topLevelScopes: List<FirScope>,
    private val localScopes: List<FirLocalScope>,
    override val implicitReceiverStack: ImplicitReceiverStack,
    private val qualifiedResolver: FirQualifiedNameResolver
) : BodyResolveComponents by transformer {

    fun resolveCallAndSelectCandidate(functionCall: FirFunctionCall, expectedTypeRef: FirTypeRef?, file: FirFile): FirFunctionCall {
        qualifiedResolver.reset()
        @Suppress("NAME_SHADOWING")
        val functionCall = (functionCall.transformExplicitReceiver(transformer, noExpectedType) as FirFunctionCall)
            .transformArguments(transformer, null) as FirFunctionCall

        val name = functionCall.calleeReference.name

        val explicitReceiver = functionCall.explicitReceiver
        val arguments = functionCall.arguments
        val typeArguments = functionCall.typeArguments

        val info = CallInfo(
            CallKind.Function,
            explicitReceiver,
            arguments,
            functionCall.safe,
            typeArguments,
            session,
            file,
            transformer.container
        ) { it.resultType }
        val towerResolver = FirTowerResolver(
            returnTypeCalculator, this, resolutionStageRunner,
            topLevelScopes = topLevelScopes.asReversed(),
            localScopes = localScopes.asReversed()
        )

        val consumer = createFunctionConsumer(session, name, info, this, towerResolver.collector, towerResolver)
        val result = towerResolver.runResolver(consumer, implicitReceiverStack.receiversAsReversed())
        val bestCandidates = result.bestCandidates()
        val reducedCandidates = if (result.currentApplicability < CandidateApplicability.SYNTHETIC_RESOLVED) {
            bestCandidates.toSet()
        } else {
            ConeOverloadConflictResolver(TypeSpecificityComparator.NONE, inferenceComponents)
                .chooseMaximallySpecificCandidates(bestCandidates, discriminateGenerics = false)
        }


/*
        fun isInvoke()

        val resultExpression =

        when {
            successCandidates.singleOrNull() as? ConeCallableSymbol -> {
                FirFunctionCallImpl(functionCall.session, functionCall.psi, safe = functionCall.safe).apply {
                    calleeReference =
                        functionCall.calleeReference.transformSingle(this@FirBodyResolveTransformer, result.successCandidates())
                    explicitReceiver =
                        FirQualifiedAccessExpressionImpl(
                            functionCall.session,
                            functionCall.calleeReference.psi,
                            functionCall.safe
                        ).apply {
                            calleeReference = createResolvedNamedReference(
                                functionCall.calleeReference,
                                result.variableChecker.successCandidates() as List<ConeCallableSymbol>
                            )
                            explicitReceiver = functionCall.explicitReceiver
                        }
                }
            }
            is ApplicabilityChecker -> {
                functionCall.transformCalleeReference(this, result.successCandidates())
            }
            else -> functionCall
        }
*/
        val nameReference = createResolvedNamedReference(
            functionCall.calleeReference,
            reducedCandidates,
            result.currentApplicability
        )

        val resultExpression = functionCall.transformCalleeReference(StoreNameReference, nameReference) as FirFunctionCall
        val candidate = resultExpression.candidate()

        // We need desugaring
        val resultFunctionCall = if (candidate != null && candidate.callInfo != info) {
            functionCall.copy(
                explicitReceiver = candidate.callInfo.explicitReceiver,
                dispatchReceiver = candidate.dispatchReceiverExpression(),
                extensionReceiver = candidate.extensionReceiverExpression(),
                arguments = candidate.callInfo.arguments,
                safe = candidate.callInfo.isSafeCall
            )
        } else {
            resultExpression
        }
        val typeRef = typeFromCallee(resultFunctionCall)
        if (typeRef.type is ConeKotlinErrorType) {
            resultFunctionCall.resultType = typeRef
        }
        return resultFunctionCall
    }

    fun <T : FirQualifiedAccess> resolveVariableAccessAndSelectCandidate(qualifiedAccess: T, file: FirFile): FirStatement {
        val callee = qualifiedAccess.calleeReference as? FirSimpleNamedReference ?: return qualifiedAccess

        qualifiedResolver.initProcessingQualifiedAccess(qualifiedAccess, callee)

        @Suppress("NAME_SHADOWING")
        val qualifiedAccess = qualifiedAccess.transformExplicitReceiver(transformer, noExpectedType)
        qualifiedResolver.replacedQualifier(qualifiedAccess)?.let { return it }

        val info = CallInfo(
            CallKind.VariableAccess,
            qualifiedAccess.explicitReceiver,
            emptyList(),
            qualifiedAccess.safe,
            emptyList(),
            session,
            file,
            transformer.container
        ) { it.resultType }
        val towerResolver = FirTowerResolver(
            returnTypeCalculator, this, resolutionStageRunner,
            topLevelScopes = topLevelScopes.asReversed(),
            localScopes = localScopes.asReversed()
        )

        val consumer = createVariableAndObjectConsumer(
            session,
            callee.name,
            info, this,
            towerResolver.collector
        )
        val result = towerResolver.runResolver(consumer, implicitReceiverStack.receiversAsReversed())

        val candidates = result.bestCandidates()
        val nameReference = createResolvedNamedReference(
            callee,
            candidates,
            result.currentApplicability
        )

        if (qualifiedAccess.explicitReceiver == null &&
            (candidates.size <= 1 && result.currentApplicability < CandidateApplicability.SYNTHETIC_RESOLVED)
        ) {
            qualifiedResolver.tryResolveAsQualifier()?.let { return it }
        }

        val referencedSymbol = when (nameReference) {
            is FirResolvedCallableReference -> nameReference.resolvedSymbol
            is FirNamedReferenceWithCandidate -> nameReference.candidateSymbol
            else -> null
        }
        if (referencedSymbol is FirClassLikeSymbol<*>) {
            return FirResolvedQualifierImpl(nameReference.psi, referencedSymbol.classId).apply {
                resultType = typeForQualifier(this)
            }
        }

        if (qualifiedAccess.explicitReceiver == null) {
            qualifiedResolver.reset()
        }

        @Suppress("UNCHECKED_CAST")
        var resultExpression = qualifiedAccess.transformCalleeReference(StoreNameReference, nameReference) as T
        if (candidates.size == 1) {
            val candidate = candidates.single()
            resultExpression = resultExpression.transformDispatchReceiver(StoreReceiver, candidate.dispatchReceiverExpression()) as T
            resultExpression = resultExpression.transformExtensionReceiver(StoreReceiver, candidate.extensionReceiverExpression()) as T
        }
        if (resultExpression is FirExpression) transformer.storeTypeFromCallee(resultExpression)
        return resultExpression
    }

    private fun createResolvedNamedReference(
        namedReference: FirNamedReference,
        candidates: Collection<Candidate>,
        applicability: CandidateApplicability
    ): FirNamedReference {
        val name = namedReference.name
        val psi = namedReference.psi
        return when {
            candidates.isEmpty() -> FirErrorNamedReference(
                psi, "Unresolved name: $name"
            )
            applicability < CandidateApplicability.SYNTHETIC_RESOLVED -> {
                FirErrorNamedReference(
                    psi,
                    "Inapplicable($applicability): ${candidates.map { describeSymbol(it.symbol) }}",
                    namedReference.name
                )
            }
            candidates.size == 1 -> {
                val candidate = candidates.single()
                val coneSymbol = candidate.symbol
                when {
                    coneSymbol is FirBackingFieldSymbol -> FirBackingFieldReferenceImpl(psi, coneSymbol)
                    coneSymbol is FirVariableSymbol && (
                            coneSymbol !is FirPropertySymbol ||
                                    (coneSymbol.phasedFir(session) as FirMemberDeclaration).typeParameters.isEmpty()
                            ) ->
                        FirResolvedCallableReferenceImpl(psi, name, coneSymbol)
                    else -> FirNamedReferenceWithCandidate(psi, name, candidate)
                }
            }
            else -> FirErrorNamedReference(
                psi, "Ambiguity: $name, ${candidates.map { describeSymbol(it.symbol) }}",
                namedReference.name
            )
        }
    }


    private fun describeSymbol(symbol: AbstractFirBasedSymbol<*>): String {
        return when (symbol) {
            is FirClassLikeSymbol<*> -> symbol.classId.asString()
            is FirCallableSymbol<*> -> symbol.callableId.toString()
            else -> "$symbol"
        }
    }
}
