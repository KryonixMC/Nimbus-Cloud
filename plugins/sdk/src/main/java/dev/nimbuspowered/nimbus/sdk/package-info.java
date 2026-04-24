/**
 * Public, stable Nimbus SDK surface for Minecraft plugins running inside
 * Nimbus-managed services.
 *
 * <p>Classes in this package and its sub-packages form the supported API
 * contract unless annotated with
 * {@link org.jetbrains.annotations.ApiStatus.Internal @ApiStatus.Internal}.
 * Breaking changes to unannotated classes happen only in major Nimbus
 * versions with a documented migration path; annotated classes are
 * implementation details and may change without notice between minor
 * versions.
 *
 * <p>Typical entry points: {@link dev.nimbuspowered.nimbus.sdk.Nimbus} for
 * self-service info inside a managed server, and
 * {@link dev.nimbuspowered.nimbus.sdk.NimbusClient} /
 * {@link dev.nimbuspowered.nimbus.sdk.NimbusEventStream} for proxy/external
 * plugins talking to the controller.
 */
package dev.nimbuspowered.nimbus.sdk;
