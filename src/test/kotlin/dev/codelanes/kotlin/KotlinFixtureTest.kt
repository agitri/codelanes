package dev.codelanes.kotlin

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Everything later tasks build on: calls, lambdas, callable references and Java calls resolve in K2 test mode. */
class KotlinFixtureTest : KotlinTestCase() {
    private fun repo() {
        addFile("acme/Repo.kt", """
            package acme
            class Repo { fun save(id: Int) {} }
        """)
    }

    /** What the first reference of [element] resolves to. */
    private fun resolved(element: PsiElement): PsiElement? = element.references.firstNotNullOfOrNull { it.resolve() }

    private fun assertIsRepoSave(target: PsiElement?) {
        assertTrue("Expected Repo.save, got $target", target is KtNamedFunction)
        target as KtNamedFunction
        assertEquals("save", target.name)
        assertEquals("Repo", (target.parent.parent as KtClass).name)
        assertEquals("Repo.kt", target.containingFile.name)
    }

    fun testACallResolvesIntoAnotherFile() {
        repo()
        val file = open("acme/Service.kt", """
            package acme
            class Service(private val repo: Repo) { fun go() { repo.save(1) } }
        """)
        val call = PsiTreeUtil.findChildOfType(file, KtCallExpression::class.java)!!
        assertIsRepoSave(resolved(call.calleeExpression!!))
    }

    fun testACallInsideALambdaResolves() {
        repo()
        val file = open("acme/Service.kt", """
            package acme
            class Service(private val repo: Repo) { fun go() { run { repo.save(1) } } }
        """)
        val call = PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java).first { it.calleeExpression?.text == "save" }
        assertIsRepoSave(resolved(call.calleeExpression!!))
    }

    fun testACallableReferenceResolves() {
        repo()
        val file = open("acme/Service.kt", """
            package acme
            class Service(private val repo: Repo) { fun go() { val f = repo::save } }
        """)
        val ref = PsiTreeUtil.findChildOfType(file, KtCallableReferenceExpression::class.java)!!
        assertIsRepoSave(resolved(ref.callableReference))
    }

    fun testAKotlinCallResolvesToJava() {
        addFile("acme/JavaRepo.java", "package acme; public class JavaRepo { public String find() { return \"\"; } }")
        val file = open("acme/Service.kt", """
            package acme
            fun go() { JavaRepo().find() }
        """)
        val call = PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java).first { it.calleeExpression?.text == "find" }
        val target = resolved(call.calleeExpression!!)
        assertTrue("Expected a PsiMethod, got $target", target is PsiMethod)
        assertEquals("find", (target as PsiMethod).name)
        assertEquals("JavaRepo.java", target.containingFile.name)
    }

    fun testImplementationsOfAKotlinInterfaceFunction() {
        val store = addFile("acme/Store.kt", """
            package acme
            interface Store { fun put() }
        """)
        addFile("acme/DbStore.kt", """
            package acme
            class DbStore : Store { override fun put() {} }
        """)
        val put = PsiTreeUtil.findChildOfType(store, KtNamedFunction::class.java)!!
        val found = inBackground { DefinitionsScopedSearch.search(put).findAll().toList() }
        assertEquals(listOf("DbStore.kt"), found.map { it.containingFile.name })
        assertTrue(found.single() is KtNamedFunction)
    }

    fun testSupportHandlesKtAndRejectsScripts() {
        val kt = addFile("acme/a.kt", "package acme")
        val kts = addFile("acme/b.kts", "val x = 1")
        val support = KotlinLanguageSupport()
        assertTrue(support.handles(kt.virtualFile))
        assertFalse(support.handles(kts.virtualFile))
        assertEquals("Kotlin scripts aren't supported yet", support.unsupportedReason(kts))
        assertNull(support.unsupportedReason(kt))
    }
}
