/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v2_3.planDescription

import org.neo4j.cypher.internal.compiler.v2_3.planDescription.InternalPlanDescription.Arguments._

object renderPlanAndRuntime extends (InternalPlanDescription => String) {
  def apply(plan: InternalPlanDescription): String = {
    val runtime = plan.arguments.collectFirst { case Runtime(r) => r.toString }.getOrElse("UNKNOWN")
    val planner = plan.arguments.collectFirst { case Planner(p) => p.toString }.getOrElse("UNKNOWN")

    s"PLANNER $planner\nRUNTIME $runtime"
  }
}

