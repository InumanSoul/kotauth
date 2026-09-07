package com.kauth.domain.scim

/**
 * Marks a [ScimResource] as the complete, fully merged desired state for a resource — the only
 * shape `toDomain` on a SCIM mapper may accept. An attribute absent from a [ScimResource] means
 * "clear this field"; that reading is correct for PUT, where the request body already is the
 * desired end state, and for a PATCH once its operations have been merged onto
 * `toResource(existing)` (see [ScimPatchEngine]). It is wrong — and silently destructive — for a
 * raw PATCH body, where an unmentioned attribute means "leave alone", not "clear".
 *
 * The constructor is private on purpose: a bare public constructor would compile identically for
 * both cases, so it catches "forgot to wrap" but not "wrapped the wrong thing" — the actual
 * catastrophe. The two named factories below make each call site say which case it is in:
 * [fromFullReplace] for PUT/POST, where the request body genuinely is the desired end state,
 * and — for PATCH — [ScimPatchEngine.applyMerged], which is the *only* way to obtain a
 * merge-flavored instance; there is deliberately no public factory that builds one from an
 * arbitrary [ScimResource], so a PATCH route cannot construct one without actually calling the
 * patch engine.
 */
@JvmInline
value class MergedScimResource private constructor(
    val value: ScimResource,
) {
    companion object {
        /**
         * PUT / POST: the request body already is the complete desired state — there is nothing
         * to merge. Use only at a PUT/POST call site; a PATCH route must go through
         * [ScimPatchEngine.applyMerged] instead.
         */
        fun fromFullReplace(resource: ScimResource): MergedScimResource = MergedScimResource(resource)

        /**
         * Not part of the public contract of this type — [ScimPatchEngine.applyMerged] is the
         * sole caller. Kept `internal` rather than `private` only because the engine lives in a
         * different class; do not call this directly from a route or a mapper.
         */
        internal fun fromPatchEngineResult(resource: ScimResource): MergedScimResource = MergedScimResource(resource)
    }
}
