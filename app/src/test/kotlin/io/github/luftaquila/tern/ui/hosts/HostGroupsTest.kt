// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.hosts

import io.github.luftaquila.tern.data.Host
import io.github.luftaquila.tern.data.groupNames
import io.github.luftaquila.tern.data.tagNames
import kotlin.test.Test
import kotlin.test.assertEquals

class HostGroupsTest {

    private fun host(id: String, group: String = "", tags: List<String> = emptyList()) = Host(id = id, hostname = id, group = group, tags = tags)

    @Test
    fun hostsWithoutAGroupComeFirstThenGroupsByName() {
        val hosts = listOf(host("a", "web"), host("b"), host("c", "DB"), host("d", "Web"), host("e"))
        val sections = hostSections(hosts)
        assertEquals(listOf("", "db", "web"), sections.map { it.key })
        assertEquals(listOf(listOf("b", "e"), listOf("c"), listOf("a", "d")), sections.map { s -> s.hosts.map { it.id } }, "order within a group kept")
        assertEquals("web", sections.last().name, "groups differing in case are one, named like their first host's")
        assertEquals(listOf("a"), hostSections(listOf(host("a", "web"))).flatMap { s -> s.hosts.map { it.id } }, "no empty section for hosts without a group")
    }

    @Test
    fun typedTagsAreTidied() {
        assertEquals(listOf("web"), withTag(emptyList(), "  #web ", emptyList()))
        assertEquals(listOf("web"), withTag(listOf("web"), "WEB", emptyList()), "a tag already there is not added again")
        assertEquals(listOf("Prod"), withTag(emptyList(), "prod", listOf("Prod")), "spelled like the tag other hosts have")
        assertEquals(emptyList(), withTag(emptyList(), " # ", emptyList()))
        assertEquals(listOf("web", "db", "Prod"), withTags(listOf("web"), "db, prod,, Web", listOf("Prod")), "comma-separated, each tidied")
        assertEquals("two words", Host.groupOf("  two \t words "))
    }

    @Test
    fun namesInUse() {
        val hosts = listOf(host("a", "web", listOf("b", "A")), host("b", "Web", listOf("a")), host("c", "db"))
        assertEquals(listOf("db", "web"), hosts.groupNames())
        assertEquals(listOf("A", "b"), hosts.tagNames())
    }
}
