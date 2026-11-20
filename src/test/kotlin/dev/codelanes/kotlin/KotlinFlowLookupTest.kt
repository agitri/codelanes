package dev.codelanes.kotlin

import dev.codelanes.flow.Flow
import dev.codelanes.flow.FlowEntry
import dev.codelanes.flow.FlowHop
import dev.codelanes.flow.FlowResolver
import dev.codelanes.flow.ResolvedFlow
import dev.codelanes.flow.ResolvedHop
import dev.codelanes.java.JavaLanguageSupport
import dev.codelanes.lang.FlowLookup

class KotlinFlowLookupTest : KotlinTestCase() {
    override fun setUp() {
        super.setUp()
        addFile("acme/Note.kt", "package acme\n\nclass Note")
        addFile("acme/NoteSaved.kt", "package acme\n\nclass NoteSaved(val note: Note)")
        addFile("acme/Repo.kt", """
            package acme

            interface Store { fun save(note: Note) }

            class Repo : Store {
                override fun save(note: Note) {}
                fun load() {}
            }
        """)
        addFile("acme/NoteViewModel.kt", """
            package acme

            class NoteViewModel(private val repo: Repo) {
                fun save() { repo.save(Note()) }
                fun make() { Repo() }
                fun fire() { NoteSaved(Note()) }
                fun viaLambda() { listOf(1).forEach { repo.load() } }
                fun other() {}
            }
        """)
        addFile("acme/notes/NoteScreen.kt", """
            package acme.notes

            fun NoteScreen() { helper() }
            fun helper() {}
        """)
        addFile("acme/NoteListener.kt", "package acme\n\nclass NoteListener { fun on(event: NoteSaved) {} fun nope(n: Note) {} }")
        addFile("acme/JavaEvent.java", "package acme;\npublic class JavaEvent {}")
        addFile("acme/JavaListener.java", "package acme;\npublic class JavaListener { public void on(NoteSaved e) {} public void nope(Note n) {} }")
        addFile("acme/KotlinListener.kt", "package acme\n\nclass KotlinListener { fun on(event: JavaEvent) {} fun nope(n: Note) {} }")
        addFile("acme/Over.kt", "package acme\n\nclass Over { fun save(n: Int) {} fun save(s: String) {} }")
        addFile("acme/NoteService.java", "package acme;\npublic class NoteService { public void store(Note n) {} public void done() {} }")
        addFile("acme/NoteController.kt", """
            package acme

            class NoteController(private val service: NoteService) {
                fun create() { service.store(Note()) }
            }
        """)
        addFile("acme/JavaCaller.java", """
            package acme;
            public class JavaCaller {
                private final Repo repo = new Repo();
                public void run() { repo.load(); }
                public void build() { new Note(); }
            }
        """)
    }

    private fun resolve(vararg hops: FlowHop, entry: String = "acme.NoteViewModel::save"): ResolvedFlow =
        inBackground { FlowResolver.resolve(project, Flow("f", FlowEntry("f", entry), hops.toList())) }

    private fun find(reference: String): FlowLookup? = inBackground { KotlinFlowLookup.find(project, reference, KotlinLanguageSupport()) }

    private fun found(reference: String) = (find(reference) as FlowLookup.Found).target

    fun testAMemberIsFound() {
        val target = found("acme.NoteViewModel::save")
        assertEquals("acme.NoteViewModel", target.classFqn)
        assertEquals("acme.NoteViewModel", target.declaringFqn)
        assertEquals("NoteViewModel.save", target.methodKey)
        assertEquals("NoteViewModel.kt", target.file.name)
    }

    fun testATopLevelFunctionIsFoundByModule() {
        val target = found("acme.notes.NoteScreen::NoteScreen")
        assertEquals("acme.notes.NoteScreen", target.classFqn)
        assertEquals("NoteScreen", target.methodKey)
    }

    fun testAClassAndItsConstructorAreFound() {
        assertNull(found("acme.Repo::class").methodKey)
        val init = found("acme.Repo::<init>")
        assertNull(init.methodKey)
        assertEquals("acme.Repo", init.classFqn)
    }

    fun testAnInheritedMemberIsDeclaredByItsParent() {
        addFile("acme/Sub.kt", "package acme\n\nclass Sub : Repo()")
        val target = found("acme.Sub::load")
        assertEquals("acme.Sub", target.classFqn)
        assertEquals("acme.Repo", target.declaringFqn)
    }

    fun testAMissingMemberOfAKnownOwnerSaysSo() {
        val missing = find("acme.NoteViewModel::nope") as FlowLookup.Missing
        assertTrue(missing.ownerFound)
        assertTrue(missing.why, "no such" in missing.why)
        val inModule = find("acme.notes.NoteScreen::nope") as FlowLookup.Missing
        assertTrue(inModule.ownerFound)
    }

    fun testUnknownOwnerIsMissingAndBackslashIsNotKotlin() {
        val missing = find("acme.Nothing::x") as FlowLookup.Missing
        assertFalse(missing.ownerFound)
        assertNull(find("App\\Foo::x"))
    }

    fun testTheEntryResolvesToAKotlinMethodId() {
        val resolved = resolve()
        assertNull(resolved.entryProblem)
        assertEquals("NoteViewModel.kt", resolved.entryFile!!.name)
        assertEquals("method:NoteViewModel.save", resolved.entryId)
    }

    fun testHopsToOtherFilesAreCallees() {
        val resolved = resolve(FlowHop("acme.NoteViewModel::save", "acme.Repo::save", "calls"))
        assertEquals(listOf(ResolvedHop("method:NoteViewModel.save", "callee:acme.Repo::save", "calls", true)), resolved.hops)
        assertEquals(setOf("acme.Repo::save"), resolved.added)
    }

    private fun proven(from: String, to: String, kind: String, entry: String = from): Boolean =
        resolve(FlowHop(from, to, kind), entry = entry).hops.single().proven

    fun testACallIsProven() {
        assertTrue(proven("acme.NoteViewModel::save", "acme.Repo::save", "calls"))
        assertFalse(proven("acme.NoteViewModel::other", "acme.Repo::save", "calls"))
    }

    fun testACallInALambdaIsProven() {
        assertTrue(proven("acme.NoteViewModel::viaLambda", "acme.Repo::load", "calls"))
    }

    fun testAConstructionIsProvenForAClassTarget() {
        assertTrue(proven("acme.NoteViewModel::make", "acme.Repo::class", "calls"))
        assertTrue(proven("acme.NoteViewModel::fire", "acme.NoteSaved::class", "dispatches"))
        assertFalse(proven("acme.NoteViewModel::other", "acme.NoteSaved::class", "dispatches"))
    }

    fun testATopLevelCallIsProven() {
        assertTrue(proven("acme.notes.NoteScreen::NoteScreen", "acme.notes.NoteScreen::helper", "calls"))
    }

    fun testBindsIsProvenWhenTheTargetOverridesTheSource() {
        assertTrue(proven("acme.Store::save", "acme.Repo::save", "binds"))
        assertFalse(proven("acme.Repo::load", "acme.Repo::save", "binds"))
    }

    fun testListensIsProvenByTheFirstParameterType() {
        assertTrue(proven("acme.NoteSaved::class", "acme.NoteListener::on", "listens", entry = "acme.NoteListener::on"))
        assertFalse(proven("acme.NoteSaved::class", "acme.NoteListener::nope", "listens", entry = "acme.NoteListener::on"))
    }

    fun testRoutesIsNeverProven() {
        assertFalse(proven("acme.NoteViewModel::save", "acme.Repo::save", "routes"))
    }

    fun testHopFromKotlinToJavaIsProven() {
        assertTrue(proven("acme.NoteController::create", "acme.NoteService::store", "calls"))
        assertFalse(proven("acme.NoteController::create", "acme.NoteService::done", "calls"))
    }

    fun testHopFromJavaToKotlinIsProven() {
        assertTrue(proven("acme.JavaCaller::run", "acme.Repo::load", "calls"))
        assertFalse(proven("acme.JavaCaller::run", "acme.Repo::save", "calls"))
        assertTrue(proven("acme.JavaCaller::build", "acme.Note::class", "calls"))
    }

    fun testJavaDoesNotClaimKotlinClasses() {
        val java = JavaLanguageSupport()
        assertNull(inBackground { java.findFlowTarget(project, "acme.Repo::load") })
        assertNull(inBackground { java.findFlowTarget(project, "acme.Repo::class") })
        assertTrue(inBackground { java.findFlowTarget(project, "acme.NoteService::store") } is FlowLookup.Found)
    }

    fun testListensFromAJavaEventToAKotlinListenerIsProven() {
        assertTrue(proven("acme.JavaEvent::class", "acme.KotlinListener::on", "listens", entry = "acme.KotlinListener::on"))
        assertFalse(proven("acme.JavaEvent::class", "acme.KotlinListener::nope", "listens", entry = "acme.KotlinListener::on"))
    }

    fun testListensFromAKotlinEventToAJavaListenerIsProven() {
        assertTrue(proven("acme.NoteSaved::class", "acme.JavaListener::on", "listens", entry = "acme.JavaListener::on"))
        assertFalse(proven("acme.NoteSaved::class", "acme.JavaListener::nope", "listens", entry = "acme.JavaListener::on"))
    }

    fun testAnOverloadIsPickedBySignature() {
        assertEquals("Over.save(Int)", found("acme.Over::save(Int)").methodKey)
        assertEquals("Over.save(String)", found("acme.Over::save(String)").methodKey)
        assertTrue(find("acme.Over::save(Long)") is FlowLookup.Missing)
    }

    fun testASignatureOnANonOverloadedFunctionStillResolves() {
        assertEquals("Repo.save", found("acme.Repo::save(Note)").methodKey)
        assertEquals("Repo.load", found("acme.Repo::load()").methodKey)
        assertEquals("NoteScreen", found("acme.notes.NoteScreen::NoteScreen()").methodKey)
    }

    fun testAWrongSignatureOnAnOverloadedFunctionIsMissing() {
        assertTrue(find("acme.Over::save(Long)") is FlowLookup.Missing)
    }

    fun testAnInheritedOverloadIsFoundByFullKeyBeforeTheBareNameFallback() {
        addFile("acme/Base.kt", "package acme\n\nopen class Base { fun save(s: String) {} }")
        addFile("acme/Derived.kt", "package acme\n\nclass Derived : Base() { fun save(n: Int) {} }")
        val target = found("acme.Derived::save(String)")
        assertEquals("acme.Base", target.declaringFqn)
        assertEquals("Base.save", target.methodKey)
        assertEquals("Derived.save", found("acme.Derived::save(Int)").methodKey)
    }
}
