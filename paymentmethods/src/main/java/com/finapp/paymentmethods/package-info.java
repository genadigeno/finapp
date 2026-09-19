/**
 * The tokenised-instrument boundary - the platform's PCI line.
 *
 * <p><strong>What belongs here.</strong> The PaymentMethod aggregate ({@code P5-TSK-004}): a
 * token reference to an external instrument plus display metadata - brand, a provider-supplied
 * display suffix, an expiry month - and <em>structurally nothing else</em>. No PAN, no CVV, no
 * track data, nothing from which an instrument could be reconstructed, ever ({@code INV-PAY-02});
 * a tokenisation provider being unavailable fails the operation and never falls back to holding
 * raw detail ({@code MODULE_ARCHITECTURE.md} §4, bounded context 10, and M7 - the module exists
 * so that "no raw card data crosses this line" is a boundary that can be reviewed on ONE
 * module's surface rather than a convention inside a large payments module).
 *
 * <p><strong>The most isolated business module on the platform, deliberately.</strong> This
 * module sees no business sibling, and no business sibling sees it - {@code payments} included,
 * whose refusal has no Gradle cycle behind it and rests entirely on the isolation tests: the
 * module that talks to providers reaches an instrument through a port {@code app} implements,
 * never through an import. A PCI boundary that depended on business siblings would have the
 * whole dependency ball as its review surface.
 *
 * <p><strong>What exists so far.</strong> The boundary and the migrator-owned schema
 * ({@code P5-TSK-001}) - nothing else. The aggregate and its schema are {@code P5-TSK-004}; the
 * attach/detach/list surface with its step-up point is {@code P5-TSK-005}.
 *
 * <p><strong>Deliberately no audit-action enum yet</strong> (the deliberately-few licence):
 * attach and detach are audited as the person by the surface whose design fixes them
 * ({@code P5-TSK-005}).
 */
package com.finapp.paymentmethods;
