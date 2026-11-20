package dev.codelanes.kotlin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import dev.codelanes.canvas.BlocksSession
import dev.codelanes.settings.PinStore

/**
 * A Kotlin file driven through the session. The session's synchronous rebuild (on open) runs on the calling
 * thread, the EDT here and in the IDE, where K2 refuses DefinitionsScopedSearch (ProhibitedAnalysisException).
 */
class KotlinSessionTest : KotlinTestCase() {
    private var session: BlocksSession? = null

    override fun tearDown() {
        try {
            session?.let { Disposer.dispose(it) }
        } finally {
            super.tearDown()
        }
    }

    private fun openSession(path: String, text: String, follow: String? = null): BlocksSession {
        val psi = addFile(path, text)
        PinStore.getInstance(project).prune(psi.virtualFile.path, emptySet())
        assertTrue("the test must run on the EDT", ApplicationManager.getApplication().isDispatchThread)
        return BlocksSession(project, psi.virtualFile).also { session = it }
    }

    fun testOpeningAnInterfaceCanvasOnTheEdtShowsImplementers() {
        addFile("acme/DbStore.kt", "package acme\n\nclass DbStore : Store { override fun put() {} }")
        val session = openSession("acme/Store.kt", "package acme\n\ninterface Store { fun put() }")
        assertTrue(session.blockIds().contains("implementer:acme.DbStore"))
    }

    fun testFollowingAnInterfaceCallOnTheEdtShowsImplementations() {
        addFile("acme/Store.kt", "package acme\n\ninterface Store { fun put() }")
        addFile("acme/DbStore.kt", "package acme\n\nclass DbStore : Store { override fun put() {} }")
        val session = openSession("acme/Svc.kt", "package acme\n\nfun run(store: Store) { store.put() }")
        session.followChain("method:run", 2)
        assertTrue(session.blockIds().contains("callee:acme.DbStore::put"))
    }
}
