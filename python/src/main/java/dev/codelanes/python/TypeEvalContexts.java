package dev.codelanes.python;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.jetbrains.python.psi.types.TypeEvalContext;

/**
 * {@code TypeEvalContext.codeAnalysis} from Java: it is a static method in every PythonCore version, but from 261 on it
 * is written in Kotlin ({@code @JvmStatic} in a companion), so a Kotlin call compiled against 261 goes through
 * {@code TypeEvalContext.Companion}, which 2025.2 and 2025.3 don't have. Java always calls the static method.
 */
public final class TypeEvalContexts {
    private TypeEvalContexts() {
    }

    public static TypeEvalContext codeAnalysis(Project project, PsiFile file) {
        return TypeEvalContext.codeAnalysis(project, file);
    }
}
