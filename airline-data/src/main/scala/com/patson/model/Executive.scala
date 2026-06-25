package com.patson.model

/**
  * A C-suite executive: the single-player "strategy layer" that sits above the fungible Manager pool
  * (see [[Manager]]). Where managers are interchangeable labor leveled by tenure-in-task, an executive
  * occupies one named seat that governs an operational domain and — from Phase 1 on — buffs that
  * domain while drawing a salary.
  *
  * Phase 0 is data-only: the roster persists and renders, but no buffs, salary, leveling, or traits
  * are applied yet. Those arrive in later phases (see docs/executive-team-plan.md). Everything here is
  * pure and unit-testable; persistence lives in [[com.patson.data.ExecutiveSource]].
  *
  * @param level   1-based seat level (drives buff strength / salary from Phase 2). Starts at 1.
  * @param xp      domain-performance experience accrued toward the next level (Phase 2). Starts at 0.
  * @param traitKey optional specialization key (Phase 3). None until traits ship.
  */
case class Executive(airline : Airline,
                     role : ExecutiveRole.Value,
                     level : Int,
                     xp : Int,
                     hiredCycle : Int,
                     salary : Int,
                     traitKey : Option[String] = None,
                     var id : Int = 0) extends IdObject

/**
  * The fixed set of C-suite seats. Persisted by enum id (mirrors how [[ManagerTaskType]] is stored as
  * an int), so the declared order must never be reordered — append new seats only.
  *
  * Phase 1 ships only the three seats that map onto the most common pain points (CFO/CCO/COO); the
  * rest are declared now (free — they are just enum values) but not yet offered.
  */
object ExecutiveRole extends Enumeration {
  type ExecutiveRole = Value
  val CFO, CCO, COO, CMO, CHRO, CSO = Value

  /** Seats offered in Phase 1. The others are declared for forward-compat but not appointable yet. */
  val phase1Roles : Seq[ExecutiveRole] = Seq(CFO, CCO, COO)

  /** Human title for the panel. */
  def displayName(role : ExecutiveRole) : String = role match {
    case CFO  => "Chief Financial Officer"
    case CCO  => "Chief Commercial Officer"
    case COO  => "Chief Operating Officer"
    case CMO  => "Chief Marketing Officer"
    case CHRO => "Chief People Officer"
    case CSO  => "Chief Strategy Officer"
  }

  /** One-line description of the domain a seat governs (shown on locked/empty seats). */
  def domain(role : ExecutiveRole) : String = role match {
    case CFO  => "Finance: loan rates, fuel cost, dividends"
    case CCO  => "Commercial: pricing, demand capture, route advice"
    case COO  => "Operations: maintenance, delays, fleet condition"
    case CMO  => "Marketing: advertising, reputation"
    case CHRO => "People: service quality, manager development"
    case CSO  => "Strategy: network & base expansion"
  }
}
