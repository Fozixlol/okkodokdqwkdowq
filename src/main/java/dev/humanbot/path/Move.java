package dev.humanbot.path;

/** The kinds of step the pathfinder can plan, modelled on Baritone's. */
public enum Move
{
	/** Start of a path. */
	START,
	/** Walk one block straight. */
	TRAVERSE,
	/** Walk one block diagonally. */
	DIAGONAL,
	/** Jump up one block. */
	ASCEND,
	/** Step or fall down 1..n blocks. */
	DESCEND,
	/** Jump straight up, placing a block underneath (tower up). */
	PILLAR,
	/** Walk over a gap by sneaking to the edge and placing a block. */
	BRIDGE,
	/** Sprint-jump across a 1-2 block gap. */
	PARKOUR
}
