package pairing

import "strings"

// The magic words and the emoji row lean on ancient Rome, like the rest of
// Styli. Themed lists the user can choose are planned; this is the default.
//
// Security does not depend on the words being Roman, only on how many there
// are and that each is picked at random: with one guess allowed and 4 words
// from this list, an attacker's chance is 1 in len(words)^4.

// words: easy to read aloud and type, lowercase a–z only, no duplicates
// (checked by a test).
var words = strings.Fields(`
amber amphora anchor anvil apple aqueduct arch archer arena armor arrow
atlas atrium augur aurum axle
banner barley basilica basket bath beacon beech bell bench birch bishop
blade boar bough bow bowl brass bread breeze bridge bronze brook buckle
bull
camp candle canyon capitol carriage cart castle cedar census chain chalk
chariot cherry chest chisel cicada circus citadel citizen clay cliff cloak
clover cobble coin colony column comet consul copper coral courier cove
crane crest crown cypress
dagger dawn deer delta denarius dew dial dolphin dome dove dragon drum
dune
eagle elder elm ember emperor empire envoy
falcon farm feast fennel fern festival fig flame flask flax fleet flint
flute forge fort forum fountain fox frost
galley garden garland garlic gate gem giant gladius glass goat goblet
gold gorge granary grape gravel griffin grove guard
hall hammer harbor harp harvest hawk hazel hearth hedge helm helmet herald
heron hill hive honey horn horse
ibis inkwell iron island ivory ivy
jasper javelin juniper jupiter
kettle kiln knot
ladder lake lamp lance lantern lark laurel lava ledger legion lemon lentil
lily linen lion lotus lute lyre
mallet maple marble market marsh mars meadow medal mercury mill mint
minerva mirror mist moon mosaic moss mountain mule myrtle
nectar neptune nest net nutmeg
oak oar oasis oath olive onyx oracle orchard otter owl
paddock palace palm papyrus parchment pasture path pearl pebble pepper
pillar pine plank plow plume pomegranate pond poppy port praetor prism
province
quail quarry quartz quill quiver
raven reed relic ribbon ridge river road robe rope rose ruby rudder rune
saddle saffron sage sail salt sandal saturn scale scroll scythe seal
senate shell shield shrine sickle signet silk silver slate sling snail
sparrow spear spindle spring spruce stable stag star statue steppe stone
storm stream stylus summit swan sword
tablet temple thistle thread throne thyme tide timber toga torch tower
trident tribune trumpet tunic
umbra urn
valley vase vault venus vesta victory villa vine violet vulcan
wagon walnut wax wheat wheel willow wind wolf wool wreath
yarrow yew
zephyr
`)
