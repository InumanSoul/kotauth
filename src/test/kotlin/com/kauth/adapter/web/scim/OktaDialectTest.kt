package com.kauth.adapter.web.scim

import com.kauth.domain.scim.ScimFailure
import com.kauth.domain.scim.ScimValue
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class OktaDialectTest {
    @Test
    fun `an advisory display sub-attribute is dropped from members`() {
        val ops = OktaDialect.normalizeOps(fixture("okta/patch-add-member-with-display.json")).getOrThrow()
        val member = ((ops.single().value as ScimValue.MultiValued).values.single() as ScimValue.Complex)
        assertEquals("42", (member.attributes["value"] as ScimValue.Str).value)
        assertNull(member.attributes["display"])
    }

    @Test
    fun `a deactivation with a proper path passes through unchanged`() {
        val body = fixture("okta/patch-deactivate-user.json")
        assertEquals(RfcDialect.normalizeOps(body).getOrThrow(), OktaDialect.normalizeOps(body).getOrThrow())
    }

    @Test
    fun `a group resource keeps its member ids and drops their display`() {
        val resource = OktaDialect.normalizeResource(fixture("okta/group-with-member-display.json")).getOrThrow()
        assertEquals(ScimValue.Str("Engineering"), resource.attributes["displayName"])
        val member = (resource.attributes["members"] as ScimValue.MultiValued).values.single() as ScimValue.Complex
        assertEquals(ScimValue.Str("42"), member.attributes["value"])
        assertNull(member.attributes["display"])
    }

    @Test
    fun `display is dropped from members carried in a pathless partial resource`() {
        val body =
            Json.parseToJsonElement(
                """{"Operations":[{"op":"add","value":{"members":[{"value":"42","display":"Ada"}]}}]}""",
            )
        val merged =
            OktaDialect
                .normalizeOps(body)
                .getOrThrow()
                .single()
                .value as ScimValue.Complex
        val member = (merged.attributes["members"] as ScimValue.MultiValued).values.single() as ScimValue.Complex
        assertEquals(ScimValue.Str("42"), member.attributes["value"])
        assertNull(member.attributes["display"])
    }

    @Test
    fun `a bare id at the members target fails rather than being read as a member`() {
        val body = Json.parseToJsonElement("""{"Operations":[{"op":"add","path":"members","value":"42"}]}""")
        assertIs<ScimFailure>(OktaDialect.normalizeOps(body).exceptionOrNull())
    }

    @Test
    fun `a members entry that is not an object fails rather than being read as a member`() {
        val body = Json.parseToJsonElement("""{"Operations":[{"op":"add","path":"members","value":["42"]}]}""")
        assertIs<ScimFailure>(OktaDialect.normalizeOps(body).exceptionOrNull())
    }

    @Test
    fun `a filtered member path is left exactly as it arrived`() {
        val body =
            Json.parseToJsonElement(
                """{"Operations":[{"op":"remove","path":"members[value eq \"42\"]"}]}""",
            )
        assertEquals(RfcDialect.normalizeOps(body).getOrThrow(), OktaDialect.normalizeOps(body).getOrThrow())
    }

    @Test
    fun `a display sub-attribute outside members is left alone`() {
        val body = Json.parseToJsonElement("""{"schemas":[],"emails":[{"value":"ada@example.com","display":"Ada"}]}""")
        val emails = OktaDialect.normalizeResource(body).getOrThrow().attributes["emails"] as ScimValue.MultiValued
        assertEquals(ScimValue.Str("Ada"), (emails.values.single() as ScimValue.Complex).attributes["display"])
    }

    @Test
    fun `the dialect is reachable by its persisted id`() {
        assertEquals(OktaDialect, scimDialectFor("okta"))
    }
}
