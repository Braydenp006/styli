package pairing

// emoji for the pairing check, leaning on the Roman world where an emoji
// exists for it. Each is a single code point that shows as a picture by
// default, so terminals and browsers draw it alike, and each has a name for
// screen readers and for reading aloud. 64 of them: 6 shown give 36 bits.
var emoji = []struct{ Char, Name string }{
	{"🏺", "amphora"}, {"🍇", "grapes"}, {"🦅", "eagle"}, {"🐺", "wolf"},
	{"🫒", "olive"}, {"🌿", "herb"}, {"🍯", "honey"}, {"🔥", "fire"},
	{"🌙", "moon"}, {"⭐", "star"}, {"🌊", "wave"}, {"🐎", "horse"},
	{"🦁", "lion"}, {"🐂", "ox"}, {"🐐", "goat"}, {"🐑", "sheep"},
	{"🐟", "fish"}, {"🐍", "snake"}, {"🦉", "owl"}, {"🐝", "bee"},
	{"🦢", "swan"}, {"🌾", "wheat"}, {"🌳", "tree"}, {"🌲", "pine"},
	{"🍷", "wine"}, {"🍞", "bread"}, {"🧀", "cheese"}, {"🍎", "apple"},
	{"🍐", "pear"}, {"🍋", "lemon"}, {"🍒", "cherries"}, {"🌹", "rose"},
	{"🌻", "sunflower"}, {"🏰", "castle"}, {"🏹", "bow"}, {"🔱", "trident"},
	{"🪙", "coin"}, {"📜", "scroll"}, {"🔔", "bell"}, {"🏆", "trophy"},
	{"👑", "crown"}, {"💎", "gem"}, {"🔑", "key"}, {"🪔", "oil lamp"},
	{"🎭", "theatre masks"}, {"⛵", "sailboat"}, {"⚓", "anchor"}, {"🐚", "shell"},
	{"🦂", "scorpion"}, {"🦀", "crab"}, {"🐬", "dolphin"}, {"🐢", "turtle"},
	{"🦌", "deer"}, {"🐗", "boar"}, {"🐓", "rooster"}, {"🦚", "peacock"},
	{"🍄", "mushroom"}, {"🌰", "chestnut"}, {"🥚", "egg"}, {"🌋", "volcano"},
	{"🎺", "trumpet"}, {"🥁", "drum"}, {"🎲", "dice"}, {"⛲", "fountain"},
}
