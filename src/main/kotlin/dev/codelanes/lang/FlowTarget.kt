package dev.codelanes.lang

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * What a flow reference points at. [classFqn]: the class that was named, spelled as block ids spell it
 * (`\App\Foo`, `com.acme.Foo`). [declaringFqn]: the class that declares the method (differs for inherited ones).
 * [methodKey]: the method part of its block id, or null for the class as a whole. [file]: the declaring class's file.
 * [classElement] and [methodElement] are for [LanguageSupport.proves] of the same [support] only.
 */
data class FlowTarget(
    val classFqn: String,
    val declaringFqn: String,
    val methodKey: String?,
    val isConstructor: Boolean,
    val file: PsiFile,
    val classElement: PsiElement,
    val methodElement: PsiElement?,
    val support: LanguageSupport,
)

sealed interface FlowLookup {
    data class Found(val target: FlowTarget) : FlowLookup
    /**
     * [ownerFound]: the language knows the module or class but not the member, so its [why] is the one to report
     * when several languages miss the reference.
     */
    data class Missing(val why: String, val ownerFound: Boolean = false) : FlowLookup
}
