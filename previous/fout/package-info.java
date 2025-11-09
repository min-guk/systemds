/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/**
 * Output Constraint Validation Framework for Federated Execution.
 *
 * This package provides validators for checking FOUT (Federated Output) constraints
 * on Hop operations during federated compilation planning.
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li><b>OP Type Filtering:</b> Only OP Types with FOUT restrictions need validators</li>
 *   <li><b>Default Allow:</b> If no validator matches, FOUT is allowed by default</li>
 *   <li><b>No Propagation:</b> Unlike FederatedTypeHandler, validators only check constraints</li>
 *   <li><b>Input-Aware:</b> Validators can inspect input FTypes for conditional checks</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 * <pre>
 * OutputConstraintValidatorFactory (OP Type filter)
 *   └─→ getValidator(hop) returns validator or null
 *        ├─ null → FOUT allowed (OP Type not in restriction list)
 *        └─ validator → validate(hop, inputTypes)
 *             ├─ OutputConstraintResult.allowed()
 *             ├─ OutputConstraintResult.disallowed()
 *             └─ OutputConstraintResult.conditional()
 * </pre>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * OutputConstraintValidatorFactory factory = new OutputConstraintValidatorFactory();
 * OutputConstraintValidator validator = factory.getValidator(hop);
 *
 * if (validator != null) {
 *     OutputConstraintResult result = validator.validate(hop, inputFTypes);
 *
 *     if (!result.isFoutAllowed()) {
 *         // FOUT blocked → force LOUT
 *         LOG.warn("FOUT blocked for " + hop.getOpString() + ": " + result.getConstraintMessage());
 *         return FType.LOCAL;
 *     } else if (result.getConstraintMessage().startsWith("CONDITIONAL")) {
 *         // FOUT allowed with constraints → log warning
 *         LOG.info("FOUT conditional: " + result.getConstraintMessage());
 *     }
 * }
 * // validator == null → FOUT allowed (no restrictions for this OP Type)
 * }</pre>
 *
 * <h2>Registered OP Types</h2>
 * Based on FOUT Constraint Table:
 * <ul>
 *   <li><b>AggregateUnary:</b> uack+, uark+, uarimax, uarimin, var (scalar output)</li>
 *   <li><b>AggregateBinary:</b> ba+* / mmult (PART output warning, MV→LOUT)</li>
 *   <li><b>MAPMM:</b> mapmm, pmmj, cpmm, rmm (PART special handling)</li>
 *   <li><b>Tsmm:</b> tsmm (converts to BROADCAST)</li>
 *   <li><b>ParameterizedBuiltin:</b> contains (boolean scalar)</li>
 *   <li><b>AggregateTernary:</b> tak*, tack+ (scalar output)</li>
 *   <li><b>MMChain:</b> mmchain (requires local aggregation)</li>
 *   <li><b>QuantilePick:</b> qpick (COL/FULL only, ROW→LOUT)</li>
 *   <li><b>Ctable:</b> ctable (requires isFedOutput() check)</li>
 * </ul>
 *
 * @see org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers for FType propagation
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fout;
