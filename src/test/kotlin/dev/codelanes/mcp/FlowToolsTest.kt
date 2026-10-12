package dev.codelanes.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FlowToolsTest : BasePlatformTestCase() {
    fun testTheToolsAreRegisteredWithTheMcpServer() {
        val toolsets = ExtensionPointName.create<McpToolset>("com.intellij.mcpServer.mcpToolset").extensionList
        assertTrue(toolsets.any { it is FlowTools })
    }

    fun testTheToolNamesAreTheOnesTheDocsMention() {
        val names = FlowTools::class.java.methods.mapNotNull { it.getAnnotation(McpTool::class.java)?.name }.sorted()
        assertEquals(listOf("codelanes_list_flows", "codelanes_show_flow"), names)
    }
}
