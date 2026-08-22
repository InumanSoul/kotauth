package com.kauth.domain.scim

// A ScimValue.Null attribute (explicitly cleared, see ScimResource.kt) is treated the
// same as an absent one throughout this engine: cleared and never-set both mean "no
// value here" for PATCH purposes, even though the distinction still matters for PUT
// semantics at the mapping layer.

enum class ScimPatchOpType { ADD, REPLACE, REMOVE }

/** A single PATCH operation. A null [path] means [value] is a partial resource to merge. */
data class ScimPatchOp(
    val op: ScimPatchOpType,
    val path: ScimPath?,
    val value: ScimValue?,
)

private val KNOWN_ATTRIBUTES =
    setOf("userName", "externalId", "displayName", "active", "name", "emails", "members", "id", "schemas", "meta")

// The target arity of a path is a property of the attribute's definition, not of what
// happens to be stored under it right now — inferring arity from a (possibly absent)
// runtime value is what let ADD collapse a collection into a scalar.
private val MULTI_VALUED_ATTRIBUTES = setOf("members", "emails")

// Sub-attribute vocabulary is only pinned down for the complex attributes this
// implementation actually supports; attributes not listed here go unchecked at this level.
private val KNOWN_SUB_ATTRIBUTES =
    mapOf(
        "name" to setOf("givenName", "familyName"),
        "emails" to setOf("value", "type", "primary"),
        // RFC 7643 §4.2 defines this set for the Group "members" attribute.
        "members" to setOf("value", "\$ref", "type", "display"),
    )

class ScimPatchEngine {
    /** Applies [ops] in order to a copy of [resource]. All-or-nothing: [resource] is never mutated. */
    fun apply(
        resource: ScimResource,
        ops: List<ScimPatchOp>,
    ): Result<ScimResource> =
        try {
            var current = resource
            for (op in ops) {
                current = applyOp(current, op)
            }
            Result.success(current)
        } catch (e: PatchException) {
            Result.failure(ScimFailure(e.type, e.message ?: "invalid patch operation"))
        }

    private fun applyOp(
        resource: ScimResource,
        op: ScimPatchOp,
    ): ScimResource {
        val path = op.path ?: return mergePartial(resource, op)
        return when (path) {
            is ScimPath.Attr -> applyAttr(resource, op, path)
            is ScimPath.Valued -> applyValued(resource, op, path)
        }
    }

    private fun mergePartial(
        resource: ScimResource,
        op: ScimPatchOp,
    ): ScimResource {
        if (op.op == ScimPatchOpType.REMOVE) {
            throw PatchException(ScimErrorType.noTarget, "remove requires a path")
        }
        val partial =
            op.value as? ScimValue.Complex
                ?: throw PatchException(ScimErrorType.invalidValue, "a null path requires a complex value")
        partial.attributes.keys.forEach(::requireKnownAttribute)
        return resource.copy(attributes = resource.attributes + partial.attributes)
    }

    private fun applyAttr(
        resource: ScimResource,
        op: ScimPatchOp,
        path: ScimPath.Attr,
    ): ScimResource {
        requireKnownAttribute(path.name)
        path.sub?.let { requireKnownSubAttribute(path.name, it) }
        return when (op.op) {
            ScimPatchOpType.REMOVE -> removeAttr(resource, path)
            ScimPatchOpType.ADD -> addAttr(resource, path, op.value)
            ScimPatchOpType.REPLACE -> replaceAttr(resource, path, op.value)
        }
    }

    private fun removeAttr(
        resource: ScimResource,
        path: ScimPath.Attr,
    ): ScimResource {
        val sub = path.sub ?: return resource.copy(attributes = resource.attributes - path.name)
        val current = resource.attributes[path.name] as? ScimValue.Complex ?: return resource
        if (sub !in current.attributes) return resource
        val updated = current.copy(attributes = current.attributes - sub)
        return resource.copy(attributes = resource.attributes + (path.name to updated))
    }

    private fun addAttr(
        resource: ScimResource,
        path: ScimPath.Attr,
        value: ScimValue?,
    ): ScimResource {
        val newValue = value ?: throw PatchException(ScimErrorType.invalidValue, "add requires a value")
        // Sub-attributes are singular, so adding one is the same as replacing it.
        if (path.sub != null) {
            return replaceAttr(resource, path, newValue)
        }
        // Whether to append is decided by the arity of the EXISTING value, not the
        // incoming one: a bare Complex added to an existing collection must still append.
        val existing = normalizeAbsent(resource.attributes[path.name])
        val merged =
            when {
                existing is ScimValue.MultiValued -> {
                    val incoming = if (newValue is ScimValue.MultiValued) newValue.values else listOf(newValue)
                    ScimValue.MultiValued(existing.values + incoming)
                }
                // The attribute is absent (or was just cleared to empty) but its definition
                // is multi-valued: the target shape still has to be a collection.
                existing == null && path.name in MULTI_VALUED_ATTRIBUTES -> {
                    val incoming = if (newValue is ScimValue.MultiValued) newValue.values else listOf(newValue)
                    ScimValue.MultiValued(incoming)
                }
                existing == null -> newValue
                // Existing is a singular scalar and the incoming value is a collection:
                // promote rather than silently drop the old value.
                newValue is ScimValue.MultiValued -> ScimValue.MultiValued(listOf(existing) + newValue.values)
                // Both singular: adding a singular value to a singular attribute is a set.
                else -> newValue
            }
        return resource.copy(attributes = resource.attributes + (path.name to merged))
    }

    private fun replaceAttr(
        resource: ScimResource,
        path: ScimPath.Attr,
        value: ScimValue?,
    ): ScimResource {
        val newValue = value ?: throw PatchException(ScimErrorType.invalidValue, "replace requires a value")
        val sub = path.sub ?: return resource.copy(attributes = resource.attributes + (path.name to newValue))
        val complex =
            when (val current = normalizeAbsent(resource.attributes[path.name])) {
                null -> ScimValue.Complex(emptyMap())
                is ScimValue.Complex -> current
                else -> throw PatchException(ScimErrorType.invalidValue, "'${path.name}' is not a complex attribute")
            }
        val updated = complex.copy(attributes = complex.attributes + (sub to newValue))
        return resource.copy(attributes = resource.attributes + (path.name to updated))
    }

    private fun applyValued(
        resource: ScimResource,
        op: ScimPatchOp,
        path: ScimPath.Valued,
    ): ScimResource {
        val attr = path.attr
        requireKnownAttribute(attr.name)
        path.sub?.let { requireKnownSubAttribute(attr.name, it) }
        // A valued path targets elements of an existing collection; nothing to target
        // when the attribute is absent means the operation is a no-op, not an error.
        val existing = normalizeAbsent(resource.attributes[attr.name]) ?: return resource
        val current =
            existing as? ScimValue.MultiValued
                ?: throw PatchException(ScimErrorType.invalidValue, "'${attr.name}' is not multi-valued")
        return when (op.op) {
            ScimPatchOpType.REMOVE -> removeValued(resource, attr, path, current)
            ScimPatchOpType.ADD, ScimPatchOpType.REPLACE -> replaceValued(resource, attr, path, current, op)
        }
    }

    private fun removeValued(
        resource: ScimResource,
        attr: ScimPath.Attr,
        path: ScimPath.Valued,
        current: ScimValue.MultiValued,
    ): ScimResource {
        val sub = path.sub
        val updated =
            if (sub == null) {
                current.values.filterNot { path.filter.matches(it) }
            } else {
                current.values.map { element ->
                    if (element is ScimValue.Complex && path.filter.matches(element)) {
                        element.copy(attributes = element.attributes - sub)
                    } else {
                        element
                    }
                }
            }
        // RFC 7644 §3.5.2.2: a collection reduced to zero elements is unassigned. Removing
        // the key here matches the plain-path remove form, so both representations of
        // "empty" converge instead of disagreeing.
        return if (sub == null && updated.isEmpty()) {
            resource.copy(attributes = resource.attributes - attr.name)
        } else {
            resource.copy(attributes = resource.attributes + (attr.name to ScimValue.MultiValued(updated)))
        }
    }

    private fun replaceValued(
        resource: ScimResource,
        attr: ScimPath.Attr,
        path: ScimPath.Valued,
        current: ScimValue.MultiValued,
        op: ScimPatchOp,
    ): ScimResource {
        val newValue = op.value ?: throw PatchException(ScimErrorType.invalidValue, "${op.op} requires a value")
        val sub = path.sub
        val updated =
            current.values.map { element ->
                if (!path.filter.matches(element)) {
                    element
                } else if (sub != null) {
                    val complex =
                        element as? ScimValue.Complex
                            ?: throw PatchException(ScimErrorType.invalidValue, "matched element is not complex")
                    complex.copy(attributes = complex.attributes + (sub to newValue))
                } else {
                    newValue
                }
            }
        return resource.copy(attributes = resource.attributes + (attr.name to ScimValue.MultiValued(updated)))
    }

    private fun requireKnownAttribute(name: String) {
        if (name !in KNOWN_ATTRIBUTES) {
            throw PatchException(ScimErrorType.invalidPath, "unknown attribute '$name'")
        }
    }

    private fun requireKnownSubAttribute(
        attrName: String,
        sub: String,
    ) {
        val allowed = KNOWN_SUB_ATTRIBUTES[attrName] ?: return
        if (sub !in allowed) {
            throw PatchException(ScimErrorType.invalidPath, "unknown sub-attribute '$attrName.$sub'")
        }
    }

    /** An explicitly cleared value is, for PATCH purposes, indistinguishable from an absent one. */
    private fun normalizeAbsent(value: ScimValue?): ScimValue? = if (value == ScimValue.Null) null else value
}

/** Internal-only: carries a typed, human-readable apply failure up to [ScimPatchEngine.apply]. */
private class PatchException(
    val type: ScimErrorType,
    message: String,
) : Exception(message)
