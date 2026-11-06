package de.drehtuer.nextalarm.domain

/** The result of a state machine step: the next state and the actions to run, in order. */
data class Transition<S, A>(val state: S, val actions: List<A> = emptyList())
