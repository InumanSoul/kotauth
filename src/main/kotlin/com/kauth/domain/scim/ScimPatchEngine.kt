package com.kauth.domain.scim

// A ScimValue.Null attribute (explicitly cleared, see ScimResource.kt) is treated the
// same as an absent one throughout this engine: cleared and never-set both mean "no
// value here" for PATCH purposes, even though the distinction still matters for PUT
// semantics at the mapping layer.
//
// That policy is about an attribute's value INSIDE a resource. The `value` member of an operation
// is a different position — the operation's argument, not an attribute — and a `remove` carrying
// an explicit null there is rejected rather than folded into "no value supplied". See applyOp.

enum class ScimPatchOpType { ADD, REPLACE, REMOVE }

/** A single PATCH operation. A null [path] means [value] is a partial resource to merge. */
data class ScimPatchOp(
    val op: ScimPatchOpType,
    val path: ScimPath?,
    val value: ScimValue?,
)

// The attribute vocabulary is SCIM_ATTRIBUTE_SHAPES' keys, so the engine and the mappers cannot
// drift into disagreeing about what a known attribute is.
private val KNOWN_ATTRIBUTES = SCIM_ATTRIBUTE_SHAPES.keys

// The target arity of a path is a property of the attribute's definition, not of what
// happens to be stored under it right now — inferring arity from a (possibly absent)
// runtime value is what let ADD collapse a collection into a scalar.
private val MULTI_VALUED_ATTRIBUTES = setOf("members", "emails")

// RFC 7644 §3.5.2.1 appends on `add` to a multi-valued attribute, which is what a group's
// membership needs. `emails` is deliberately not here: Kotauth persists exactly one address and
// renders it as a single `type: "work"` entry, so appending puts the incoming address behind the
// stored one, ScimUserMapper.selectEmail picks the stored one back, and the `add` becomes a
// silent no-op that answers 200 with the unchanged address. An `add` of `emails` therefore sets
// the collection rather than growing it — the only outcome the caller can actually observe.
private val APPEND_ON_ADD_ATTRIBUTES = setOf("members")

// Attributes whose `value` sub-attribute is a numeric id rather than free text, so entry identity
// is compared on the parsed id (see canonicalNumericIdentity).
private val NUMERIC_IDENTITY_ATTRIBUTES = setOf("members")

// Attributes RFC 7643 defines as singular *complex*, read off the one shape table. Checking it
// here as well as at the mapper's entry point costs nothing and names the offending path.
// `meta` is in this set and unreachable through it: requireWritableAttribute rejects it as
// read-only first. Derived rather than listed so it cannot drift from the table.
//
// Arity is deliberately NOT enforced here: RFC 7644 §3.5.2.1 lets an `add` or `replace` on a
// multi-valued attribute carry a single element, which this engine then folds into the collection.
// The canonical array shape is checked once, on the merged result, by ScimGroupMapper/ScimUserMapper.
private val COMPLEX_ATTRIBUTES = SCIM_ATTRIBUTE_SHAPES.filterValues { it.shape == ScimShape.COMPLEX }.keys

// Server-managed per RFC 7643: rejecting these as unknown would misdirect an integrator
// into debugging a typo that isn't there, when the real issue is a read-only target.
private val READ_ONLY_ATTRIBUTES = setOf("groups", "id", "meta", "schemas")

// Sub-attribute vocabulary is only pinned down for the complex attributes this implementation
// actually reads inside, which is exactly where the shape table declares sub-attribute shapes.
// Deriving it keeps a path check and a body check from disagreeing about what `emails.primary` is.
// An attribute with no declared sub-attributes goes unchecked at this level.
private val KNOWN_SUB_ATTRIBUTES =
    SCIM_ATTRIBUTE_SHAPES
        .filterValues { it.subAttributes.isNotEmpty() }
        .mapValues { (_, spec) -> spec.subAttributes.keys }

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

    /**
     * Same as [apply], but wraps the result as a [MergedScimResource] directly — a PATCH route
     * should call this instead of [apply], since [MergedScimResource] has no public factory that
     * accepts an arbitrary [ScimResource] for the merge case. Going through this method is what
     * proves a mapper's `toDomain` is receiving an actually-merged resource, not just a wrapped one.
     */
    fun applyMerged(
        resource: ScimResource,
        ops: List<ScimPatchOp>,
    ): Result<MergedScimResource> = apply(resource, ops).map { MergedScimResource.fromPatchEngineResult(it) }

    private fun applyOp(
        resource: ScimResource,
        op: ScimPatchOp,
    ): ScimResource {
        // RFC 7644 §3.5.2.2's "no value means remove everything" reading is about an ABSENT `value`
        // member, not an explicit JSON null. Folding the two together gives the least informative
        // input the most destructive outcome: a malformed `value` is already a 400 here, an empty
        // array removes nothing, and null would empty the whole group under a 200. The engine
        // refuses to guess at a value it cannot read (see removalIdentities) — this is the same
        // refusal. Omitting `value` entirely is still how a caller clears a collection.
        if (op.op == ScimPatchOpType.REMOVE && op.value == ScimValue.Null) {
            throw PatchException(
                ScimErrorType.invalidValue,
                "a 'remove' with an explicit null 'value' is ambiguous; omit 'value' to remove every entry",
            )
        }
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
        // A pathless partial is a body fragment, so it is judged the way a PUT body is: an
        // attribute SCIM defines and Kotauth does not persist rides along and is ignored. A partial
        // naming it is a client sending its whole record; an explicit `path` naming it is a
        // deliberate write, which stays a failure rather than a 200 that did nothing.
        partial.attributes.keys
            .filterNot(::isIgnorableScimAttribute)
            .forEach(::requireWritableAttribute)
        partial.attributes.forEach { (name, value) -> requireComplexShape(name, value) }
        if (op.op != ScimPatchOpType.ADD) {
            return resource.copy(attributes = resource.attributes + partial.attributes)
        }
        // RFC 7644 §3.5.2.1: for `add`, a multi-valued attribute inside a pathless partial keeps
        // its collection shape (appending or setting per APPEND_ON_ADD_ATTRIBUTES), and a complex
        // attribute has its named sub-attributes added without disturbing siblings that weren't
        // named — the same semantics a targeted-path `add` already has. A plain overwrite
        // (REPLACE, or ADD where neither shape applies) stays a plain merge.
        val merged =
            partial.attributes.mapValues { (name, value) ->
                val existing = normalizeAbsent(resource.attributes[name])
                when {
                    name in MULTI_VALUED_ATTRIBUTES -> addToMultiValued(name, existing, value)
                    value is ScimValue.Complex && existing is ScimValue.Complex ->
                        existing.copy(attributes = existing.attributes + value.attributes)
                    else -> value
                }
            }
        return resource.copy(attributes = resource.attributes + merged)
    }

    private fun applyAttr(
        resource: ScimResource,
        op: ScimPatchOp,
        path: ScimPath.Attr,
    ): ScimResource {
        requireWritableAttribute(path.name)
        path.sub?.let { requireKnownSubAttribute(path.name, it) }
        // Only a whole-attribute write can change the attribute's shape; a sub-attribute path
        // targets one field inside it and leaves the complex wrapper in place.
        if (path.sub == null) op.value?.let { requireComplexShape(path.name, it) }
        return when (op.op) {
            ScimPatchOpType.REMOVE -> removeAttr(resource, path, op.value)
            ScimPatchOpType.ADD -> addAttr(resource, path, op.value)
            ScimPatchOpType.REPLACE -> replaceAttr(resource, path, op.value)
        }
    }

    private fun removeAttr(
        resource: ScimResource,
        path: ScimPath.Attr,
        value: ScimValue?,
    ): ScimResource {
        val sub = path.sub ?: return removeWholeAttr(resource, path.name, value)
        val current = resource.attributes[path.name] as? ScimValue.Complex ?: return resource
        if (sub !in current.attributes) return resource
        val updated = current.copy(attributes = current.attributes - sub)
        return resource.copy(attributes = resource.attributes + (path.name to updated))
    }

    /**
     * RFC 7644 §3.5.2.2 gives `remove` on a multi-valued attribute two readings, and they are not
     * the same operation: with no `value` every element goes, with a `value` only the listed
     * elements go. Ignoring the `value` and taking the first reading empties a whole group when
     * the caller asked for one member — under a 200 that tells the connector it worked.
     */
    private fun removeWholeAttr(
        resource: ScimResource,
        name: String,
        value: ScimValue?,
    ): ScimResource {
        val supplied = normalizeAbsent(value)
        if (supplied == null || name !in MULTI_VALUED_ATTRIBUTES) {
            return resource.copy(attributes = resource.attributes - name)
        }
        // Shape-checked before the stored value is read, so the same request earns the same
        // answer whether or not the attribute happens to hold anything right now.
        val listed = removalIdentities(name, supplied)
        val current = normalizeAbsent(resource.attributes[name]) as? ScimValue.MultiValued ?: return resource
        val kept = current.values.filterNot { it.multiValuedIdentity(name) in listed }
        // Emptied to a collection rather than dropped: the mappers read an absent attribute and an
        // empty one under different rules, and only the empty one unambiguously means "no members".
        return resource.copy(attributes = resource.attributes + (name to ScimValue.MultiValued(kept)))
    }

    /**
     * The entries a valued `remove` names, keyed by the `value` sub-attribute — the identity SCIM
     * uses for a group member and for an email. A value this cannot read is a failure, never a
     * fallback to "remove everything": guessing there is what makes a typo destructive.
     */
    private fun removalIdentities(
        name: String,
        value: ScimValue,
    ): Set<String> {
        val entries =
            when (value) {
                is ScimValue.MultiValued -> value.values
                // RFC 7644 §3.5.2.1's single-element form, which `add` already accepts here.
                is ScimValue.Complex -> listOf(value)
                else ->
                    throw PatchException(
                        ScimErrorType.invalidValue,
                        "'$name' remove value must be an array of objects, got ${value.shapeName()}",
                    )
            }
        return entries
            .mapIndexed { index, entry ->
                entry.multiValuedIdentity(name)
                    ?: throw PatchException(
                        ScimErrorType.invalidValue,
                        "'$name[$index]' remove value must be an object with a 'value' sub-attribute",
                    )
            }.toSet()
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
        // The target arity is decided by the EXISTING value, not the incoming one: a bare
        // Complex added to an existing collection must stay a collection.
        val existing = normalizeAbsent(resource.attributes[path.name])
        val merged =
            when {
                existing is ScimValue.MultiValued -> addToMultiValued(path.name, existing, newValue)
                // The attribute is absent (or was just cleared to empty) but its definition
                // is multi-valued: the target shape still has to be a collection.
                existing == null && path.name in MULTI_VALUED_ATTRIBUTES ->
                    addToMultiValued(path.name, null, newValue)
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
        requireWritableAttribute(attr.name)
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

    private fun requireWritableAttribute(name: String) {
        requireKnownAttribute(name)
        if (name in READ_ONLY_ATTRIBUTES) {
            throw PatchException(ScimErrorType.mutability, "'$name' is read-only")
        }
    }

    private fun requireComplexShape(
        name: String,
        value: ScimValue,
    ) {
        if (name !in COMPLEX_ATTRIBUTES) return
        // Null is an explicit clear, which a complex attribute can legitimately take.
        if (value == ScimValue.Null) return
        ScimShape.COMPLEX.mismatch(name, value)?.let { throw PatchException(it.type, it.detail) }
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

    /**
     * `add` onto a multi-valued attribute. The result is always a collection, so the attribute's
     * arity survives; whether the existing elements survive with it is [APPEND_ON_ADD_ATTRIBUTES].
     */
    private fun addToMultiValued(
        name: String,
        existing: ScimValue?,
        incoming: ScimValue,
    ): ScimValue.MultiValued {
        val incomingValues = if (incoming is ScimValue.MultiValued) incoming.values else listOf(incoming)
        val kept =
            if (name in APPEND_ON_ADD_ATTRIBUTES) {
                (existing as? ScimValue.MultiValued)?.values.orEmpty()
            } else {
                emptyList()
            }
        return ScimValue.MultiValued(kept + incomingValues)
    }

    /** An explicitly cleared value is, for PATCH purposes, indistinguishable from an absent one. */
    private fun normalizeAbsent(value: ScimValue?): ScimValue? = if (value == ScimValue.Null) null else value

    /** RFC 7643 §2.4: the `value` sub-attribute identifies an entry of a multi-valued attribute. */
    private fun ScimValue.multiValuedIdentity(name: String): String? =
        ((this as? ScimValue.Complex)?.attributes?.get("value") as? ScimValue.Str)
            ?.value
            ?.let { if (name in NUMERIC_IDENTITY_ATTRIBUTES) canonicalNumericIdentity(it) else it }
}

// A member id is compared the way ScimGroupMapper.parseMembers reads one — trimmed and parsed —
// rather than as the raw wire string. Without this, `add` of `" 42 "` puts user 42 in the group
// and the mirror-image `remove` matches nothing, answers 200, and leaves the user's access intact:
// a deprovisioning miss reported as success. `"042"` names the same user for the same reason.
private fun canonicalNumericIdentity(raw: String): String = raw.trim().let { it.toIntOrNull()?.toString() ?: it }

/** Internal-only: carries a typed, human-readable apply failure up to [ScimPatchEngine.apply]. */
private class PatchException(
    val type: ScimErrorType,
    message: String,
) : Exception(message)
