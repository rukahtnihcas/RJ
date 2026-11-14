#!/usr/bin/env python3
"""Source of truth for TERRA's bundled field guide.

Run `python3 tools/build_catalog.py` from the project root to regenerate
app/src/main/assets/catalog.json. Link targets are a species id, "group:<LifeGroup>"
(any other member of that group found nearby) or "habitat:<HabitatType>".
"""
import json, os, sys

ALL = ["NA", "SA", "EU", "AF", "AS", "OC"]
SP, SU, AU, WI = "SPRING", "SUMMER", "AUTUMN", "WINTER"
YEAR = [SP, SU, AU, WI]
species = []


def link(type_, targets, hint):
    return {"type": type_, "targets": targets if isinstance(targets, list) else [targets], "hint": hint}


def ph(id_, name, hint):
    return {"id": id_, "name": name, "hint": hint}


def call(low, high, rmin, rmax, tonal, sounds):
    return {"lowHz": low, "highHz": high, "rateMin": rmin, "rateMax": rmax, "tonal": tonal, "sounds": sounds}


def add(id_, common, sci, group, glyph, blurb, deeper, native=(), intro=(), invasive=(), seasons=YEAR,
        habitats=(), stages=(), phenos=(), links=(), acoustic=None, visual=(), sensitive=False):
    species.append({
        "id": id_, "common": common, "scientific": sci, "group": group, "glyph": glyph,
        "nativeTo": list(native), "introducedTo": list(intro), "invasiveIn": list(invasive),
        "sensitive": sensitive, "seasons": list(seasons), "habitats": list(habitats),
        "stages": list(stages), "phenotypes": list(phenos), "links": list(links),
        "acoustic": acoustic, "visual": list(visual), "blurb": blurb, "deeper": deeper})


LEUC = ph("leucistic", "Leucistic", "Pale or patchy white plumage from a rare pigment mutation")
BIRD_STAGES = ["juvenile", "adult"]
BFLY = ["egg", "caterpillar", "chrysalis", "adult"]
BV = ["bird", "waterfowl"]

# ───────────── Birds ─────────────
add("american_robin", "American Robin", "Turdus migratorius", "BIRD", "🐦",
    "A lawn-hunting thrush whose carolling opens the dawn chorus.",
    "Robins find worms by sight, not sound — the head-tilt aims one eye at the soil. In autumn they switch to fruit and become major seed carriers.",
    native=["NA"], habitats=["PARK", "GARDEN", "WOODLAND"], stages=["nest", "juvenile", "adult"], phenos=[LEUC],
    links=[link("EATS", "common_earthworm", "Watch where it hunts on damp lawns — what is it pulling up?"),
           link("SHELTERS_IN", "group:TREE", "It builds a mud-lined nest in a tree fork")],
    acoustic=call(1800, 4000, 2, 4, True, "Rich carolling: cheerily, cheer-up, cheerio"), visual=BV)
add("european_robin", "European Robin", "Erithacus rubecula", "BIRD", "🐦",
    "Orange-breasted and bold, it sings even under winter streetlights.",
    "Both sexes hold winter territories and sing to defend them, which is why the robin is one of the few birds heard all year.",
    native=["EU", "AF", "AS"], habitats=["GARDEN", "WOODLAND", "PARK"], stages=["juvenile", "adult"], phenos=[LEUC],
    links=[link("EATS", ["common_earthworm", "group:INSECT"], "It follows anything that disturbs soil"),
           link("SHELTERS_IN", ["english_ivy", "group:TREE"], "Look for dense evergreen cover nearby")],
    acoustic=call(2500, 7500, 4, 10, True, "Thin, silvery, wistful warble"), visual=BV)
add("house_sparrow", "House Sparrow", "Passer domesticus", "BIRD", "🐦",
    "The chattering companion of human settlement for ten thousand years.",
    "Chicks are raised almost entirely on insects, so a street without bugs is a street without sparrows — their decline tracks the loss of urban invertebrates.",
    native=["EU", "AS", "AF"], intro=["NA", "SA", "OC"], habitats=["GARDEN", "PARK", "CANOPY"],
    stages=["juvenile", "adult female", "adult male"], phenos=[LEUC],
    links=[link("EATS", "group:INSECT", "Chicks need insects — find what the parents are catching"),
           link("SHELTERS_IN", ["english_ivy", "group:TREE"], "Colonies roost in dense hedges and creepers")],
    acoustic=call(2000, 6000, 2, 5, False, "Insistent cheep, chirrup, cheep"), visual=BV)
add("rock_pigeon", "Rock Pigeon", "Columba livia", "BIRD", "🕊",
    "A cliff-nester that mistook our buildings for sea cliffs.",
    "Town flocks carry many colour morphs because they descend from domestic breeds. They are the staple that lets falcons live downtown.",
    native=["EU", "AS", "AF"], intro=["NA", "SA", "OC"], habitats=["PARK", "CANOPY"], stages=BIRD_STAGES,
    phenos=[ph("blue_bar", "Blue-bar (wild type)", "Grey with two black wing bars"),
            ph("checker", "Checker", "Wings chequered dark"), ph("pied", "Pied", "Splashed with white"),
            ph("red", "Ash-red", "Rusty brown replaces the grey")],
    links=[link("EATEN_BY", "peregrine_falcon", "Something hunts these flocks from the tallest ledges"),
           link("EATS", "group:PLANT", "Find the weedy seeds it gleans")],
    acoustic=call(250, 700, 0.5, 2, True, "Rolling, bubbling coo"), visual=BV + ["pigeon"])
add("peregrine_falcon", "Peregrine Falcon", "Falco peregrinus", "BIRD", "🦅",
    "The fastest animal alive, now nesting on bridges and towers.",
    "Recovered from near-extinction after DDT was banned. Nest ledges are reused for decades, which is exactly why their locations are kept obscured.",
    native=ALL, sensitive=True, habitats=["PARK", "WATER", "CANOPY"], stages=["eyas (chick)", "juvenile", "adult"],
    links=[link("EATS", ["rock_pigeon", "european_starling", "group:BIRD"], "Document the flocks it hunts"),
           link("SHELTERS_IN", "habitat:WATER", "Open sky over water gives it room to stoop")],
    acoustic=call(2000, 4000, 4, 7, False, "Harsh, scolding kak-kak-kak near the nest"), visual=BV + ["falcon", "eagle"])
add("red_tailed_hawk", "Red-tailed Hawk", "Buteo jamaicensis", "BIRD", "🦅",
    "The broad-winged watcher of roadsides and field edges.",
    "A hawk on a pole is a sign of a working food web below: rough grass, the voles within it, and a tall perch to watch from.",
    native=["NA", "SA"], habitats=["MEADOW", "PARK", "WOODLAND"], stages=["juvenile", "adult"],
    phenos=[ph("dark_morph", "Dark morph", "Chocolate-brown body, still with the brick-red tail"), LEUC],
    links=[link("SHELTERS_IN", "group:TREE", "Find the tall perching tree it watches from"),
           link("EATS", ["eastern_gray_squirrel", "group:MAMMAL"], "Document its prey — squirrels and voles"),
           link("EATS", "habitat:MEADOW", "Log it over the rough grass that feeds its prey")],
    acoustic=call(2000, 4500, 0.3, 1, False, "Hoarse, descending scream: keee-eeer"), visual=BV + ["hawk", "eagle"])
add("barn_owl", "Barn Owl", "Tyto alba", "BIRD", "🦉",
    "A pale ghost quartering rough grassland at dusk.",
    "Its heart-shaped face is a sound dish; it can strike a vole in total darkness. One pair needs thousands of small mammals a year — and the tussocky grass that grows them.",
    native=ALL, sensitive=True, habitats=["MEADOW", "WETLAND"], stages=["owlet", "adult"],
    links=[link("EATS", "group:MAMMAL", "Small mammals are almost its entire diet"),
           link("EATS", "habitat:MEADOW", "Log it over tussocky, unmown grass")],
    acoustic=call(1500, 4000, 0.3, 1, False, "A long, rasping shriek — never a hoot"), visual=BV + ["owl"])
add("great_horned_owl", "Great Horned Owl", "Bubo virginianus", "BIRD", "🦉",
    "The deep-voiced night hunter of woods and suburbs.",
    "It nests in midwinter, taking over old hawk and crow nests, so its hooting duets peak when the trees are bare.",
    native=["NA", "SA"], habitats=["WOODLAND", "PARK"], stages=["owlet", "adult"],
    links=[link("SHELTERS_IN", "group:TREE", "It needs big trees with old stick nests"),
           link("EATS", "group:MAMMAL", "Rabbits, squirrels and skunks are all on the menu")],
    acoustic=call(200, 500, 1, 2.5, True, "Deep, stuttering hoots: hoo, hoo-hoo, hoo, hoo"), visual=BV + ["owl"])
add("mallard", "Mallard", "Anas platyrhynchos", "BIRD", "🦆",
    "The familiar dabbling duck, ancestor of most farmyard ducks.",
    "In late summer drakes moult into a drab 'eclipse' plumage and briefly cannot fly — look for the tell-tale yellow bill.",
    native=["NA", "EU", "AS", "AF"], intro=["OC"], habitats=["WATER", "WETLAND", "PARK"],
    stages=["duckling", "female", "male"],
    phenos=[ph("eclipse", "Eclipse drake", "Late-summer male in hen-like plumage with a yellow bill"),
            ph("breeding", "Breeding drake", "Glossy green head and curled tail feathers")],
    links=[link("SHELTERS_IN", ["cattail", "habitat:WETLAND"], "Hens hide nests in waterside vegetation"),
           link("EATS", "group:PLANT", "It dabbles for seeds and shoots")],
    acoustic=call(500, 3000, 2, 5, False, "The classic descending quack-quack-quack"), visual=BV + ["duck", "goose"])
add("barn_swallow", "Barn Swallow", "Hirundo rustica", "BIRD", "🐦",
    "A fork-tailed long-distance migrant that feeds entirely on the wing.",
    "Each bird crosses continents twice a year. Its arrival date is one of the oldest phenology records humans keep.",
    native=ALL, seasons=[SP, SU, AU], habitats=["MEADOW", "WATER", "WETLAND"], stages=["nest", "juvenile", "adult"],
    links=[link("EATS", "group:INSECT", "Find the flying insects it is hawking"),
           link("SHELTERS_IN", "habitat:WATER", "It needs wet mud nearby to build its nest cup")],
    acoustic=call(2500, 7000, 5, 12, False, "Cheerful twittering ending in a dry rattle"), visual=BV)
add("great_tit", "Great Tit", "Parus major", "BIRD", "🐦",
    "A bold, black-capped acrobat of woods and feeders.",
    "It times its brood to the brief peak of oak caterpillars. As springs warm, birds and caterpillars are drifting out of step.",
    native=["EU", "AS", "AF"], habitats=["WOODLAND", "GARDEN", "PARK"], stages=["fledgling", "adult"],
    links=[link("EATS", "group:INSECT", "Find the caterpillars it feeds its brood"),
           link("SHELTERS_IN", ["english_oak", "silver_birch", "group:TREE"], "It nests in tree holes")],
    acoustic=call(3000, 6500, 2, 5, True, "A two-note see-saw: tea-cher, tea-cher"), visual=BV)
add("black_capped_chickadee", "Black-capped Chickadee", "Poecile atricapillus", "BIRD", "🐦",
    "A tiny, curious bird that stays through the hardest winters.",
    "Each autumn it grows new neurons to remember thousands of seed caches. More 'dee' notes in its call means a more dangerous predator.",
    native=["NA"], habitats=["WOODLAND", "GARDEN", "PARK"], stages=["fledgling", "adult"],
    links=[link("SHELTERS_IN", ["silver_birch", "group:TREE"], "It excavates nests in soft, rotting wood"),
           link("EATS", "group:INSECT", "In summer it gleans insects from bark and leaves")],
    acoustic=call(3000, 7500, 2, 6, True, "Whistled fee-bee, and a buzzy chick-a-dee-dee-dee"), visual=BV)
add("northern_cardinal", "Northern Cardinal", "Cardinalis cardinalis", "BIRD", "🐦",
    "The crested red songster of hedges and backyards.",
    "The male's red comes from pigments in its food. A rare mutation blocks the conversion, producing a yellow cardinal — roughly one in a million.",
    native=["NA"], habitats=["GARDEN", "PARK", "SCRUB"], stages=["juvenile", "adult female", "adult male"],
    phenos=[ph("yellow", "Yellow morph", "A golden bird where red should be — exceptionally rare"), LEUC],
    links=[link("SHELTERS_IN", "group:TREE", "It nests low in dense shrubs and vines"),
           link("EATS", "group:PLANT", "Seeds and berries colour its feathers")],
    acoustic=call(1500, 5000, 2, 5, True, "Loud slurred whistles: cheer, cheer, birdie-birdie"), visual=BV)
add("american_goldfinch", "American Goldfinch", "Spinus tristis", "BIRD", "🐦",
    "A late-nesting finch that waits for thistledown.",
    "One of very few songbirds that moults twice a year, so the same bird is lemon-yellow in July and olive-drab in January.",
    native=["NA"], habitats=["MEADOW", "GARDEN"], stages=["juvenile", "adult"],
    phenos=[ph("breeding", "Breeding plumage", "Brilliant yellow with a black cap"),
            ph("winter", "Winter plumage", "Soft olive-brown with buffy wing bars")],
    links=[link("EATS", ["purple_coneflower", "common_dandelion"], "It feeds on seedheads — leave them standing"),
           link("SHELTERS_IN", "habitat:MEADOW", "Log it in weedy, unmown ground")],
    acoustic=call(3000, 6500, 3, 8, True, "Bouncing flight call: po-ta-to-chip"), visual=BV)
add("ruby_throated_hummingbird", "Ruby-throated Hummingbird", "Archilochus colubris", "BIRD", "🐦",
    "A three-gram migrant that crosses the Gulf of Mexico non-stop.",
    "Red tubular flowers evolved around this bird's bill. Its spring arrival tracks the northward bloom of its nectar plants.",
    native=["NA", "SA"], seasons=[SP, SU, AU], habitats=["GARDEN", "WOODLAND", "MEADOW"], stages=["female", "male"],
    links=[link("POLLINATES", ["wild_bergamot", "group:PLANT"], "Find the tubular flowers it visits")], visual=BV)
add("european_starling", "Common Starling", "Sturnus vulgaris", "BIRD", "🐦",
    "A glossy mimic famous for its swirling evening murmurations.",
    "About a hundred birds released in New York in 1890 became some two hundred million, out-competing native hole-nesters across a continent.",
    native=["EU", "AS"], intro=["OC", "AF", "SA"], invasive=["NA"], habitats=["PARK", "MEADOW", "CANOPY"],
    stages=["juvenile", "adult"],
    phenos=[ph("breeding", "Breeding plumage", "Glossy purple-green with a yellow bill"),
            ph("winter", "Winter plumage", "Covered in white spots, dark bill")],
    links=[link("EATS", ["common_earthworm", "group:INSECT"], "It probes lawns for grubs"),
           link("EATEN_BY", "peregrine_falcon", "Murmurations are a defence — against what?")],
    acoustic=call(1500, 7000, 4, 12, False, "Wheezes, clicks, rattles and stolen phrases"), visual=BV)
add("common_blackbird", "Common Blackbird", "Turdus merula", "BIRD", "🐦",
    "The fluting evening voice of gardens.",
    "Blackbirds strip ivy and rowan berries in winter and deposit the seeds far away — a quiet forester of hedgerows.",
    native=["EU", "AS", "AF"], intro=["OC"], habitats=["GARDEN", "WOODLAND", "PARK"],
    stages=["juvenile", "adult female", "adult male"], phenos=[LEUC],
    links=[link("EATS", "common_earthworm", "Watch it listen and stab on wet lawns"),
           link("DISPERSES", ["english_ivy", "group:TREE"], "Find the winter berries it carries away")],
    acoustic=call(1500, 4000, 2, 4, True, "Slow, mellow fluting that ends in a squeaky flourish"), visual=BV)
add("common_myna", "Common Myna", "Acridotheres tristis", "BIRD", "🐦",
    "A confident, yellow-masked starling of towns and fields.",
    "Beloved at home, but among the world's most damaging invasive birds elsewhere, evicting native parrots from nest hollows.",
    native=["AS"], intro=["NA"], invasive=["OC", "AF"], habitats=["PARK", "GARDEN", "CANOPY"], stages=BIRD_STAGES,
    links=[link("SHELTERS_IN", "group:TREE", "It nests in tree hollows — and fights for them"),
           link("EATS", "group:INSECT", "It follows grazing animals and mowers for flushed insects")],
    acoustic=call(1000, 5000, 2, 6, False, "Gurgles, squawks and whistles in rapid mix"), visual=BV)
add("house_crow", "House Crow", "Corvus splendens", "BIRD", "🐦‍⬛",
    "The grey-collared crow that lives wherever people do.",
    "It is found almost nowhere without humans. Its nests are the chosen nursery of the Asian koel.",
    native=["AS"], invasive=["AF"], habitats=["PARK", "CANOPY", "GARDEN"], stages=["nest", "juvenile", "adult"],
    links=[link("SHELTERS_IN", ["banyan", "neem", "group:TREE"], "Find the big trees where it roosts communally"),
           link("EATEN_BY", "asian_koel", "Another bird is hiding eggs in its nest — listen for it")],
    acoustic=call(800, 2200, 1, 3, False, "Flat, nasal kaaw, kaaw"), visual=BV + ["crow"])
add("asian_koel", "Asian Koel", "Eudynamys scolopaceus", "BIRD", "🐦‍⬛",
    "A hidden cuckoo whose rising call announces the hot season.",
    "A brood parasite: the female slips her egg into a crow's nest. As a fig-eater she is also a key planter of banyans.",
    native=["AS", "OC"], seasons=[SP, SU], habitats=["GARDEN", "PARK", "WOODLAND"], stages=["juvenile", "female", "male"],
    links=[link("BROOD_PARASITE", "house_crow", "Whose nest raises its chicks?"),
           link("DISPERSES", ["banyan", "group:TREE"], "Find the fruiting fig it feeds in")],
    acoustic=call(800, 1900, 0.5, 1.5, True, "Loud, rising ko-el, ko-el, ko-EL"), visual=BV)
add("rose_ringed_parakeet", "Rose-ringed Parakeet", "Psittacula krameri", "BIRD", "🦜",
    "A lime-green parrot at home from Sahel scrub to London parks.",
    "Escaped cage birds now breed across Europe, competing with bats and woodpeckers for tree hollows.",
    native=["AS", "AF"], intro=["EU", "NA"], habitats=["PARK", "GARDEN", "CANOPY"], stages=["juvenile", "female", "male"],
    phenos=[ph("lutino", "Lutino", "All-yellow escapee morph"), ph("blue", "Blue morph", "Sky-blue aviary mutation")],
    links=[link("SHELTERS_IN", "group:TREE", "It nests in old tree hollows"),
           link("EATS", ["neem", "banyan", "group:TREE"], "Find the fruiting tree the flock raids")],
    acoustic=call(2000, 5000, 2, 5, False, "Shrill, squealing kee-ak, kee-ak"), visual=BV + ["parrot"])
add("australian_magpie", "Australian Magpie", "Gymnorhina tibicen", "BIRD", "🐦‍⬛",
    "A black-and-white songster with a flute-like dawn carol.",
    "Family groups hold the same territory for years and recognise individual human faces.",
    native=["OC"], habitats=["PARK", "MEADOW", "GARDEN"], stages=["juvenile", "adult"],
    links=[link("EATS", ["common_earthworm", "group:INSECT"], "It listens for grubs under turf"),
           link("SHELTERS_IN", ["river_red_gum", "group:TREE"], "Find its nest tree")],
    acoustic=call(500, 3000, 2, 6, True, "Rich, yodelling carol: quardle-oodle-ardle"), visual=BV)
add("rainbow_lorikeet", "Rainbow Lorikeet", "Trichoglossus moluccanus", "BIRD", "🦜",
    "A brush-tongued parrot that chases the eucalypt bloom.",
    "Its tongue ends in a brush for mopping nectar and pollen, making it a major pollinator of gum trees.",
    native=["OC"], habitats=["PARK", "GARDEN", "WOODLAND"], stages=["juvenile", "adult"],
    links=[link("POLLINATES", ["river_red_gum", "group:TREE"], "Find the flowering tree the flock is working")],
    acoustic=call(2500, 6000, 3, 8, False, "Sharp rolling screeches in flight"), visual=BV + ["parrot"])
add("great_kiskadee", "Great Kiskadee", "Pitangus sulphuratus", "BIRD", "🐦",
    "A bold yellow flycatcher that shouts its own name.",
    "It fishes like a kingfisher, hawks insects like a flycatcher and eats fruit like a thrush — a generalist thriving in cities.",
    native=["SA", "NA"], habitats=["PARK", "WATER", "GARDEN"], stages=["juvenile", "adult"],
    links=[link("EATS", "group:INSECT", "Find what it sallies out to catch"),
           link("SHELTERS_IN", "habitat:WATER", "It often hunts from perches over water")],
    acoustic=call(1500, 4000, 1, 3, False, "Loud, exuberant kis-ka-dee!"), visual=BV)

# ───────────── Wildflowers, herbs & vines ─────────────
PV = ["plant", "flower", "petal", "flora"]
add("common_milkweed", "Common Milkweed", "Asclepias syriaca", "PLANT", "🌿",
    "A milky-sapped meadow plant with globes of fragrant pink flowers.",
    "Its toxins defend it from almost everything except a few specialists that turn the poison into their own armour.",
    native=["NA"], seasons=[SP, SU, AU], habitats=["MEADOW", "GARDEN"], stages=["shoot", "flowering", "seed pod"],
    links=[link("EATEN_BY", "monarch", "Check the leaf undersides for a striped specialist"),
           link("EATEN_BY", ["common_eastern_bumblebee", "western_honey_bee", "group:INSECT"], "Find who visits its flowers")],
    visual=PV)
add("crown_flower", "Crown Flower", "Calotropis gigantea", "PLANT", "🌿",
    "A waxy-flowered giant milkweed of waysides and wasteland.",
    "Often dismissed as a weed, it is the nursery of tiger butterflies; its floss once stuffed pillows.",
    native=["AS", "AF"], intro=["OC", "SA"], habitats=["SCRUB", "GARDEN", "MEADOW"], stages=["flowering", "seed pod"],
    links=[link("EATEN_BY", ["plain_tiger", "monarch"], "Search the leaves for banded caterpillars"),
           link("EATEN_BY", "group:INSECT", "Carpenter bees are its main pollinators — who is visiting?")], visual=PV)
add("common_dandelion", "Common Dandelion", "Taraxacum officinale", "PLANT", "🌼",
    "The sun-yellow first meal of spring for hungry pollinators.",
    "Each 'flower' is around a hundred florets. Its deep taproot mines calcium and loosens compacted soil.",
    native=["EU", "AS"], intro=["NA", "SA", "OC", "AF"], seasons=[SP, SU, AU], habitats=["GARDEN", "PARK", "MEADOW"],
    stages=["rosette", "flowering", "seed clock"],
    links=[link("EATEN_BY", ["western_honey_bee", "buff_tailed_bumblebee", "common_eastern_bumblebee", "group:INSECT"],
                "Find an early pollinator feeding on it")], visual=PV + ["dandelion"])
add("white_clover", "White Clover", "Trifolium repens", "PLANT", "☘",
    "A creeping lawn legume that feeds bees and soil alike.",
    "Bacteria in its root nodules pull nitrogen from the air, fertilising the grass around it for free.",
    native=["EU", "AS", "AF"], intro=["NA", "SA", "OC"], seasons=[SP, SU, AU], habitats=["GARDEN", "PARK", "MEADOW"],
    stages=["leaf", "flowering"], phenos=[ph("four_leaf", "Four-leaf", "A lucky mutation — roughly 1 in 5,000 leaves")],
    links=[link("EATEN_BY", ["western_honey_bee", "buff_tailed_bumblebee", "common_eastern_bumblebee"],
                "Its flowers are built for bees — find one at work")], visual=PV + ["clover"])
add("purple_coneflower", "Purple Coneflower", "Echinacea purpurea", "PLANT", "🌸",
    "A prairie daisy with a bristling orange cone.",
    "It feeds in two seasons: nectar for butterflies in summer, then oily seeds for finches if the heads are left standing.",
    native=["NA"], intro=["EU"], seasons=[SU, AU], habitats=["MEADOW", "GARDEN"], stages=["flowering", "seedhead"],
    links=[link("EATEN_BY", ["monarch", "common_eastern_bumblebee", "group:INSECT"], "Find a pollinator on the cone"),
           link("EATEN_BY", "american_goldfinch", "Who eats the seedheads in autumn?")], visual=PV)
add("wild_bergamot", "Wild Bergamot", "Monarda fistulosa", "PLANT", "🌸",
    "Shaggy lavender pom-poms smelling of oregano.",
    "Its tubular florets fit long tongues: bumblebees, hawk-moths and hummingbirds.",
    native=["NA"], seasons=[SU], habitats=["MEADOW", "GARDEN"], stages=["flowering", "seedhead"],
    links=[link("EATEN_BY", ["ruby_throated_hummingbird", "common_eastern_bumblebee", "group:INSECT"],
                "Find a long-tongued visitor")], visual=PV)
add("common_nettle", "Stinging Nettle", "Urtica dioica", "PLANT", "🌿",
    "The stinging herb that is a butterfly nursery in disguise.",
    "A nettle patch can raise several butterfly species and shelter the aphids that feed spring ladybirds.",
    native=["EU", "AS", "AF", "NA"], seasons=[SP, SU, AU], habitats=["WOODLAND", "GARDEN", "WETLAND"],
    stages=["shoot", "flowering"],
    links=[link("EATEN_BY", ["red_admiral", "painted_lady"], "Look for leaves folded into tents"),
           link("EATEN_BY", ["seven_spot_ladybird", "group:INSECT"], "Who patrols it for aphids?")], visual=PV)
add("cattail", "Broadleaf Cattail", "Typha latifolia", "PLANT", "🌾",
    "The brown-sausage reed of marsh edges.",
    "Its stands filter run-off and hide nests; a single spike releases some two hundred thousand seeds.",
    native=["NA", "EU", "AS", "AF", "SA"], habitats=["WETLAND", "WATER"], stages=["green spike", "brown spike", "seeding fluff"],
    links=[link("EATEN_BY", ["mallard", "group:BIRD"], "Which birds hide or nest in the stand?"),
           link("SHELTERS_IN", "habitat:WETLAND", "Log it in standing water or a marsh")], visual=PV)
add("white_trillium", "White Trillium", "Trillium grandiflorum", "PLANT", "🌸",
    "A three-petalled spring ephemeral of old woodland.",
    "It takes seven years or more to flower and its seeds are planted by ants. Picked and poached, colonies rarely recover.",
    native=["NA"], sensitive=True, seasons=[SP], habitats=["WOODLAND"], stages=["bud", "white flower", "pink (ageing) flower"],
    links=[link("FUNGAL_PARTNER", "group:FUNGUS", "Its roots depend on woodland fungi — find any nearby"),
           link("SHELTERS_IN", "habitat:WOODLAND", "Log it beneath a closed canopy")], visual=PV)
add("bee_orchid", "Bee Orchid", "Ophrys apifera", "PLANT", "🌸",
    "An orchid whose flower impersonates a female bee.",
    "The disguise evolved to lure male bees into mating with it. Its dust-like seed cannot germinate without a partner fungus.",
    native=["EU", "AF", "AS"], sensitive=True, seasons=[SP, SU], habitats=["MEADOW", "SCRUB"], stages=["rosette", "flowering"],
    links=[link("FUNGAL_PARTNER", "group:FUNGUS", "No fungus, no orchid — log a fungus in the same ground"),
           link("SHELTERS_IN", "habitat:MEADOW", "It needs unfertilised, lightly-mown grassland")], visual=PV)
add("garlic_mustard", "Garlic Mustard", "Alliaria petiolata", "PLANT", "🌿",
    "A garlic-scented hedgerow herb — and a woodland invader abroad.",
    "Its roots leak chemicals that poison the soil fungi native wildflowers and tree seedlings depend on.",
    native=["EU", "AS", "AF"], invasive=["NA"], seasons=[SP, SU], habitats=["WOODLAND", "PARK"],
    stages=["rosette", "flowering", "seed pods"],
    links=[link("COMPETES_WITH", ["white_trillium", "group:PLANT"], "Record which woodland flowers it is displacing")], visual=PV)
add("japanese_knotweed", "Japanese Knotweed", "Reynoutria japonica", "PLANT", "🎋",
    "Bamboo-like canes forming dense thickets along rivers and rail lines.",
    "A fingernail of root regrows a whole stand. Never cut and dump it — report it to the land manager.",
    native=["AS"], invasive=["EU", "NA", "OC"], seasons=[SP, SU, AU], habitats=["WATER", "PARK", "SCRUB"],
    stages=["red shoots", "full canes", "flowering", "dead canes"],
    links=[link("COMPETES_WITH", "group:PLANT", "Record the bankside plants at its edge")], visual=PV)
add("english_ivy", "Common Ivy", "Hedera helix", "PLANT", "🍃",
    "An evergreen climber: wildlife larder at home, smothering carpet abroad.",
    "In Europe its autumn flowers are the last big nectar source of the year. In North American forests it forms 'ivy deserts' and topples trees.",
    native=["EU", "AS", "AF"], invasive=["NA", "OC"], habitats=["WOODLAND", "GARDEN", "PARK"],
    stages=["juvenile (lobed leaves)", "flowering", "berries"],
    links=[link("EATEN_BY", ["red_admiral", "western_honey_bee", "group:INSECT"], "Who drinks from its late flowers?"),
           link("EATEN_BY", ["common_blackbird", "group:BIRD"], "Who eats or hides in it?")], visual=PV + ["ivy"])
add("lantana", "Lantana", "Lantana camara", "PLANT", "🌺",
    "A multicoloured garden shrub that has overrun forests on three continents.",
    "Butterflies love it, but it carpets forest floors and its leaves poison grazing animals.",
    native=["SA", "NA"], invasive=["AS", "AF", "OC"], habitats=["SCRUB", "WOODLAND", "GARDEN"], stages=["flowering", "berries"],
    links=[link("EATEN_BY", ["plain_tiger", "monarch", "group:INSECT"], "Which butterflies does it lure?"),
           link("COMPETES_WITH", "group:PLANT", "Record what still grows beneath it")], visual=PV)
add("water_hyacinth", "Water Hyacinth", "Pontederia crassipes", "PLANT", "🪻",
    "A beautiful floating plant that can double its mat in two weeks.",
    "Mats block light and oxygen, suffocating the water beneath. Mapping new outbreaks early is the cheapest control there is.",
    native=["SA"], invasive=["AS", "AF", "NA", "OC", "EU"], habitats=["WATER", "WETLAND"], stages=["floating rosette", "flowering"],
    links=[link("SHELTERS_IN", "habitat:WATER", "Log exactly which water body it has reached")], visual=PV)

# ───────────── Trees ─────────────
TV = ["tree", "plant", "flora", "trunk", "branch", "twig", "forest"]
OAK_PH = [ph("autumn", "Autumn colour", "Leaves turned bronze or russet"), ph("mast", "Mast year", "A bumper acorn crop")]
add("english_oak", "English Oak", "Quercus robur", "TREE", "🌳",
    "A long-lived giant that is less a tree than a town.",
    "A mature oak supports over two thousand species, hundreds found nowhere else. Veterans can pass eight hundred years.",
    native=["EU", "AS"], intro=["NA", "OC"], habitats=["WOODLAND", "PARK", "CANOPY"],
    stages=["acorn", "seedling", "sapling", "mature", "veteran"], phenos=OAK_PH,
    links=[link("FUNGAL_PARTNER", ["chicken_of_the_woods", "group:FUNGUS"], "Find a fungus on or beneath it"),
           link("EATEN_BY", ["great_tit", "group:BIRD"], "Which birds feed in its canopy?"),
           link("EATEN_BY", ["red_squirrel", "eastern_gray_squirrel", "group:MAMMAL"], "Who buries its acorns?")], visual=TV)
add("white_oak", "White Oak", "Quercus alba", "TREE", "🌳",
    "The pale-barked keystone of eastern forests.",
    "Oaks host more caterpillar species than any other North American trees — the baby food of nearly every songbird.",
    native=["NA"], habitats=["WOODLAND", "PARK", "CANOPY"], stages=["acorn", "seedling", "sapling", "mature", "veteran"],
    phenos=OAK_PH,
    links=[link("FUNGAL_PARTNER", ["chicken_of_the_woods", "group:FUNGUS"], "Find a fungus on or beneath it"),
           link("EATEN_BY", ["black_capped_chickadee", "group:BIRD"], "Which birds glean its caterpillars?"),
           link("EATEN_BY", ["eastern_gray_squirrel", "group:MAMMAL"], "Who plants its acorns?")], visual=TV)
add("silver_birch", "Silver Birch", "Betula pendula", "TREE", "🌳",
    "A white-barked pioneer, first to colonise open ground.",
    "Short-lived and generous: its roots partner with fly agaric, and its soft dead wood houses tits and woodpeckers.",
    native=["EU", "AS"], intro=["NA", "OC"], habitats=["WOODLAND", "PARK", "SCRUB"],
    stages=["seedling", "sapling", "mature", "standing dead"],
    phenos=[ph("autumn", "Autumn colour", "Leaves turned butter-yellow"), ph("catkins", "In catkin", "Spring catkins shedding pollen")],
    links=[link("FUNGAL_PARTNER", ["fly_agaric", "birch_polypore", "group:FUNGUS"], "Look for a red-capped partner at its feet")],
    visual=TV)
add("red_maple", "Red Maple", "Acer rubrum", "TREE", "🍁",
    "Red in every season: buds, flowers, leaf-stalks and autumn fire.",
    "Among the first trees to flower, feeding early bees before leaves emerge. Its autumn colour is a sunscreen for withdrawing nutrients.",
    native=["NA"], habitats=["WOODLAND", "WETLAND", "CANOPY", "PARK"], stages=["seedling", "sapling", "mature"],
    phenos=[ph("autumn", "Autumn foliage", "Crown turned scarlet"), ph("flowers", "Spring flowers", "Red tassels on bare twigs"),
            ph("samaras", "Winged seeds", "Paired red 'helicopters'")],
    links=[link("EATEN_BY", ["common_eastern_bumblebee", "western_honey_bee", "group:INSECT"], "Find an early bee at its flowers")],
    visual=TV + ["maple"])
add("white_ash", "White Ash", "Fraxinus americana", "TREE", "🌳",
    "A tall, diamond-barked tree now fighting for survival.",
    "Emerald ash borer has killed hundreds of millions of ash. Surviving 'lingering ash' are precious to breeders — map them.",
    native=["NA"], habitats=["WOODLAND", "CANOPY", "PARK"], stages=["sapling", "mature", "declining", "standing dead"],
    links=[link("EATEN_BY", "emerald_ash_borer", "Check the bark for D-shaped exit holes")], visual=TV)
add("tree_of_heaven", "Tree of Heaven", "Ailanthus altissima", "TREE", "🌳",
    "A fast, foul-smelling tree that sprouts from pavement cracks.",
    "It poisons neighbouring plants through its roots and is the favourite host of the spotted lanternfly.",
    native=["AS"], invasive=["NA", "EU", "OC", "AF"], habitats=["CANOPY", "SCRUB", "PARK"], stages=["sucker", "sapling", "mature"],
    links=[link("EATEN_BY", "spotted_lanternfly", "Inspect the trunk for a spotted sap-feeder"),
           link("COMPETES_WITH", "group:PLANT", "Record what it is shading out")], visual=TV)
add("banyan", "Banyan", "Ficus benghalensis", "TREE", "🌳",
    "A fig that walks: aerial roots become trunks until one tree is a grove.",
    "Each fig species has its own tiny wasp pollinator. Fruiting year-round, a banyan is a keystone feeding entire bird and bat communities.",
    native=["AS"], habitats=["PARK", "CANOPY", "WOODLAND"], stages=["epiphyte seedling", "young tree", "mature with prop roots", "ancient grove"],
    links=[link("EATEN_BY", ["asian_koel", "common_myna", "indian_flying_fox", "group:BIRD"], "Who carries its seeds away?"),
           link("EATEN_BY", "group:INSECT", "Every fig depends on a wasp — find any insect on it")], visual=TV)
add("neem", "Neem", "Azadirachta indica", "TREE", "🌳",
    "The village pharmacy tree, with honey-scented spring flowers.",
    "Its bitter compounds deter over two hundred insect pests, yet its flowers feed bees and its fruit feeds birds.",
    native=["AS"], intro=["AF", "SA"], invasive=["OC"], habitats=["PARK", "CANOPY", "GARDEN"], stages=["sapling", "mature", "flowering", "fruiting"],
    links=[link("EATEN_BY", ["rose_ringed_parakeet", "house_crow", "group:BIRD"], "Which birds eat the yellow fruit?"),
           link("EATEN_BY", ["western_honey_bee", "group:INSECT"], "Who visits the spring flowers?")], visual=TV)
add("river_red_gum", "River Red Gum", "Eucalyptus camaldulensis", "TREE", "🌳",
    "The great hollow-bearing eucalypt of inland waterways.",
    "Hollows take a century or more to form and house parrots, owls, bats and possums. An old tree cannot be replaced by planting.",
    native=["OC"], intro=["AF", "AS", "EU", "SA", "NA"], habitats=["WATER", "WOODLAND", "PARK"],
    stages=["seedling", "sapling", "mature", "hollow-bearing veteran"],
    links=[link("EATEN_BY", ["rainbow_lorikeet", "group:BIRD"], "Find the nectar-feeders in its bloom"),
           link("SHELTERS_IN", "habitat:WATER", "It follows rivers and floodplains")], visual=TV)

# ───────────── Fungi & lichens ─────────────
FV = ["mushroom", "fungus"]
add("fly_agaric", "Fly Agaric", "Amanita muscaria", "FUNGUS", "🍄",
    "The red-and-white toadstool of fairy tales. Poisonous.",
    "The mushroom is only the fruit. Below ground its threads sheath tree roots, trading water and minerals for sugar.",
    native=["NA", "EU", "AS"], intro=["OC", "SA", "AF"], seasons=[SU, AU], habitats=["WOODLAND", "PARK"],
    stages=["egg (button)", "domed cap", "flat cap"],
    phenos=[ph("yellow", "Yellow-orange form", "Cap orange-yellow rather than scarlet")],
    links=[link("FUNGAL_PARTNER", ["silver_birch", "group:TREE"], "Which tree is it partnered with?")], visual=FV)
add("turkey_tail", "Turkey Tail", "Trametes versicolor", "FUNGUS", "🍄",
    "Thin, velvety brackets banded like a turkey's fan.",
    "One of the great recyclers: it dismantles lignin, the toughest part of wood, returning dead trees to the soil.",
    native=ALL, habitats=["WOODLAND", "PARK", "GARDEN"], stages=["fresh brackets", "weathered brackets"],
    links=[link("DECOMPOSES", "group:TREE", "Identify the dead wood it is consuming")], visual=FV)
add("chicken_of_the_woods", "Chicken of the Woods", "Laetiporus sulphureus", "FUNGUS", "🍄",
    "Sulphur-yellow shelves erupting from old trunks.",
    "It hollows the heartwood of veteran trees — bad for timber, superb for the owls and bats that need hollows.",
    native=["NA", "EU", "AS"], seasons=[SU, AU], habitats=["WOODLAND", "PARK"], stages=["young knobs", "fresh shelves", "chalky old shelves"],
    links=[link("DECOMPOSES", ["english_oak", "white_oak", "group:TREE"], "Identify its host tree")], visual=FV)
add("birch_polypore", "Birch Polypore", "Fomitopsis betulina", "FUNGUS", "🍄",
    "A pale, rounded hoof found only on birch.",
    "Ötzi the Iceman carried it 5,300 years ago. Its old brackets become beetle nurseries.",
    native=["EU", "NA", "AS"], habitats=["WOODLAND", "PARK"], stages=["fresh bracket", "old bracket"],
    links=[link("DECOMPOSES", "silver_birch", "It grows on one kind of tree only — find it")], visual=FV)
add("shaggy_ink_cap", "Shaggy Ink Cap", "Coprinus comatus", "FUNGUS", "🍄",
    "A shaggy white cylinder that melts into black ink within a day.",
    "It digests its own cap to release spores — and its threads hunt nematode worms in the soil.",
    native=["NA", "EU", "AS"], intro=["OC"], seasons=[SP, SU, AU], habitats=["PARK", "GARDEN", "MEADOW"],
    stages=["white cylinder", "opening bell", "dripping ink"],
    links=[link("SHELTERS_IN", ["habitat:PARK", "habitat:GARDEN", "habitat:MEADOW"], "Log it in disturbed, grassy ground")], visual=FV)
add("oyster_mushroom", "Oyster Mushroom", "Pleurotus ostreatus", "FUNGUS", "🍄",
    "Pale, shell-shaped tiers on dead hardwood — even in frost.",
    "A winter-fruiting recycler that also lassoes and digests nematodes for nitrogen.",
    native=ALL, seasons=[AU, WI, SP], habitats=["WOODLAND", "PARK"], stages=["pins", "mature tiers"],
    links=[link("DECOMPOSES", "group:TREE", "Identify the log or trunk it is fruiting from")], visual=FV)
add("orange_wall_lichen", "Common Orange Lichen", "Xanthoria parietina", "FUNGUS", "🟡",
    "Egg-yolk crusts on twigs, walls and gravestones.",
    "A lichen is a fungus farming algae. This one thrives on nitrogen, so its abundance maps fertiliser and traffic pollution.",
    native=ALL, habitats=["CANOPY", "PARK", "GARDEN", "SCRUB"], stages=["leafy lobes", "with orange cups"],
    links=[link("SHELTERS_IN", "group:TREE", "Record which tree it is growing on")], visual=FV + ["lichen", "moss"])

# ───────────── Insects & kin ─────────────
IV = ["insect", "pest", "larva"]
BF = IV + ["butterfly", "moth"]
add("monarch", "Monarch", "Danaus plexippus", "INSECT", "🦋",
    "The orange-and-black migrant that crosses a continent in relays.",
    "No single butterfly completes the round trip. The autumn 'super generation' lives eight times longer than its parents to reach Mexico.",
    native=["NA", "SA"], intro=["OC", "EU"], seasons=[SP, SU, AU], habitats=["MEADOW", "GARDEN"], stages=BFLY,
    phenos=[ph("white", "White monarch (nivosus)", "Greyish-white where orange should be — very rare")],
    links=[link("HOST_PLANT", ["common_milkweed", "crown_flower"], "Its caterpillars eat one family of plant only — find it nearby"),
           link("POLLINATES", ["purple_coneflower", "wild_bergamot", "lantana", "group:PLANT"], "Find the nectar flowers fuelling the adults")],
    visual=BF)
add("plain_tiger", "Plain Tiger", "Danaus chrysippus", "INSECT", "🦋",
    "The tawny 'African monarch', painted in Egyptian tombs 3,500 years ago.",
    "Unpalatable thanks to its milkweed diet, it is copied by several harmless butterflies that borrow its warning colours.",
    native=["AF", "AS", "OC"], habitats=["SCRUB", "GARDEN", "MEADOW"], stages=BFLY,
    phenos=[ph("alcippus", "White-hindwing form", "Hindwings largely white"), ph("dorippus", "Dorippus form", "Lacks the black-and-white wingtips")],
    links=[link("HOST_PLANT", "crown_flower", "Find the giant milkweed its caterpillars eat"),
           link("POLLINATES", ["lantana", "group:PLANT"], "Find its nectar plants")], visual=BF)
add("painted_lady", "Painted Lady", "Vanessa cardui", "INSECT", "🦋",
    "The most widespread butterfly on Earth.",
    "Its 12,000 km round trip between Africa and the Arctic Circle, flown over several generations, is the longest known butterfly migration.",
    native=["NA", "EU", "AS", "AF"], seasons=[SP, SU, AU], habitats=["MEADOW", "GARDEN", "SCRUB"], stages=BFLY,
    links=[link("HOST_PLANT", ["common_nettle", "group:PLANT"], "Find the thistle or nettle its caterpillars tent"),
           link("POLLINATES", "group:PLANT", "Find its nectar flowers")], visual=BF)
add("red_admiral", "Red Admiral", "Vanessa atalanta", "INSECT", "🦋",
    "Velvet black with red sashes; a strong-flying migrant.",
    "Its caterpillar lives inside a folded nettle leaf. In autumn the adults get drunk on rotting fruit and ivy nectar.",
    native=["NA", "EU", "AS", "AF"], seasons=[SP, SU, AU], habitats=["GARDEN", "WOODLAND", "PARK"], stages=BFLY,
    links=[link("HOST_PLANT", "common_nettle", "Its caterpillars eat a stinging plant — find the patch"),
           link("POLLINATES", ["english_ivy", "group:PLANT"], "Find its late-season nectar source")], visual=BF)
BEE_ST = ["queen", "worker", "male"]
add("western_honey_bee", "Western Honey Bee", "Apis mellifera", "INSECT", "🐝",
    "The managed pollinator, dancing directions to its sisters.",
    "A returning forager's waggle dance encodes distance and direction relative to the sun. Outside its native range it is, in effect, livestock.",
    native=["EU", "AF", "AS"], intro=["NA", "SA", "OC"], seasons=[SP, SU, AU], habitats=["GARDEN", "MEADOW", "PARK"],
    stages=["worker", "swarm"],
    links=[link("POLLINATES", ["white_clover", "common_dandelion", "group:PLANT"], "Record what it is foraging on")], visual=IV + ["bee"])
add("buff_tailed_bumblebee", "Buff-tailed Bumblebee", "Bombus terrestris", "INSECT", "🐝",
    "A big, furry, early-flying bee with a buff-white tail.",
    "It 'buzz-pollinates', shaking pollen loose at a pitch near middle C — the only way tomatoes and many wildflowers release it.",
    native=["EU", "AF", "AS"], invasive=["SA", "OC"], seasons=[SP, SU, AU], habitats=["GARDEN", "MEADOW", "PARK"], stages=BEE_ST,
    links=[link("POLLINATES", ["white_clover", "common_dandelion", "group:PLANT"], "Record the flowers it works"),
           link("SHELTERS_IN", ["habitat:GARDEN", "habitat:MEADOW"], "Queens nest underground in rough, undug ground")],
    acoustic=call(150, 400, 0, 1, False, "A deep, steady, droning buzz"), visual=IV + ["bee"])
add("common_eastern_bumblebee", "Common Eastern Bumblebee", "Bombus impatiens", "INSECT", "🐝",
    "The yellow-vested bumblebee of eastern gardens.",
    "Only new queens survive winter, alone underground. Every bee you see in spring is a founding queen.",
    native=["NA"], seasons=[SP, SU, AU], habitats=["GARDEN", "MEADOW", "PARK"], stages=BEE_ST,
    links=[link("POLLINATES", ["wild_bergamot", "purple_coneflower", "common_milkweed", "white_clover", "group:PLANT"], "Record the flowers it works"),
           link("SHELTERS_IN", ["habitat:GARDEN", "habitat:MEADOW"], "Queens overwinter in leaf litter — leave the leaves")],
    acoustic=call(150, 400, 0, 1, False, "A deep, steady, droning buzz"), visual=IV + ["bee"])
add("seven_spot_ladybird", "Seven-spot Ladybird", "Coccinella septempunctata", "INSECT", "🐞",
    "The classic red beetle with seven black spots.",
    "One ladybird eats around five thousand aphids in its life. Its spiky grey larva looks nothing like the adult.",
    native=["EU", "AS", "AF"], intro=["NA"], seasons=[SP, SU, AU], habitats=["GARDEN", "MEADOW", "PARK"],
    stages=["eggs", "larva", "pupa", "adult"],
    links=[link("EATS", ["common_nettle", "common_milkweed", "group:PLANT"], "Find the aphid-laden plant it patrols"),
           link("COMPETES_WITH", "harlequin_ladybird", "A larger rival is moving in — find it")], visual=IV + ["ladybug", "beetle"])
add("harlequin_ladybird", "Harlequin Ladybird", "Harmonia axyridis", "INSECT", "🐞",
    "A shape-shifting ladybird in dozens of colour forms.",
    "Released for pest control, it now out-eats native ladybirds and preys on their larvae. Look for the 'M' behind its head.",
    native=["AS"], invasive=["EU", "NA", "SA", "AF"], habitats=["GARDEN", "PARK", "CANOPY"], stages=["eggs", "larva", "pupa", "adult"],
    phenos=[ph("succinea", "Succinea", "Orange with up to nineteen black spots"),
            ph("spectabilis", "Spectabilis", "Black with four red spots"),
            ph("conspicua", "Conspicua", "Black with two red spots")],
    links=[link("COMPETES_WITH", ["seven_spot_ladybird", "group:INSECT"], "Record the native insects sharing its plant")],
    visual=IV + ["ladybug", "beetle"])
add("green_darner", "Common Green Darner", "Anax junius", "INSECT", "🪰",
    "A big green-and-blue dragonfly that migrates like a bird.",
    "It takes three generations to complete one annual north–south migration. The nymph is a pond's top insect predator.",
    native=["NA", "SA", "AS"], seasons=[SP, SU, AU], habitats=["WATER", "WETLAND", "MEADOW"], stages=["nymph", "shed skin (exuvia)", "adult"],
    links=[link("SHELTERS_IN", ["habitat:WATER", "habitat:WETLAND"], "Its young live underwater — log it at its pond"),
           link("EATS", "group:INSECT", "Find the smaller insects it hunts")], visual=IV + ["dragonfly"])
add("emperor_dragonfly", "Emperor Dragonfly", "Anax imperator", "INSECT", "🪰",
    "A large, sky-blue dragonfly that rarely stops patrolling.",
    "It is spreading north as the climate warms — your records document that shift in real time.",
    native=["EU", "AF", "AS"], seasons=[SU, AU], habitats=["WATER", "WETLAND"], stages=["nymph", "shed skin (exuvia)", "adult"],
    links=[link("SHELTERS_IN", ["habitat:WATER", "habitat:WETLAND"], "Log it at the pond where it breeds"),
           link("EATS", "group:INSECT", "Find the insects it catches on the wing")], visual=IV + ["dragonfly"])
add("big_dipper_firefly", "Common Eastern Firefly", "Photinus pyralis", "INSECT", "✨",
    "The J-stroke flasher of warm dusk lawns.",
    "Each species has its own flash code. Larvae live two years in damp leaf litter — raked, lit-up gardens have none.",
    native=["NA"], seasons=[SU], habitats=["MEADOW", "GARDEN", "PARK", "WETLAND"], stages=["glowing larva", "adult"],
    links=[link("SHELTERS_IN", ["habitat:MEADOW", "habitat:WETLAND", "habitat:GARDEN"], "Log it over long, unlit, damp grass")],
    visual=IV)
add("field_cricket", "Field Cricket", "Gryllus sp.", "INSECT", "🦗",
    "The glossy black fiddler of warm evenings.",
    "Chirp rate rises with temperature — count chirps in a fixed interval and you have a thermometer.",
    native=["NA", "EU", "AS", "AF"], seasons=[SU, AU], habitats=["MEADOW", "GARDEN", "SCRUB"], stages=["nymph", "adult"],
    links=[link("SHELTERS_IN", ["habitat:MEADOW", "habitat:GARDEN", "habitat:SCRUB"], "Log it in the rough ground it sings from")],
    acoustic=call(3800, 5500, 2, 5, True, "Clear, musical chirp… chirp… chirp"), visual=IV + ["cricket", "grasshopper"])
add("annual_cicada", "Dog-day Cicada", "Neotibicen canicularis", "INSECT", "🪲",
    "The buzz-saw soundtrack of high summer.",
    "Nymphs spend years underground sipping root sap. Look for their split, empty shells clinging to bark.",
    native=["NA"], seasons=[SU], habitats=["CANOPY", "WOODLAND", "PARK"], stages=["shed shell", "adult"],
    links=[link("SHELTERS_IN", "group:TREE", "Find the tree it is singing from")],
    acoustic=call(4500, 9500, 0, 1, False, "A rising, whining buzz like a tiny circular saw"), visual=IV + ["cicada"])
add("emerald_ash_borer", "Emerald Ash Borer", "Agrilus planipennis", "INSECT", "🪲",
    "A metallic-green beetle and the costliest forest insect in North American history.",
    "Larvae carve S-shaped galleries under bark, girdling the tree. Never move firewood — that is how it travels.",
    native=["AS"], invasive=["NA", "EU"], seasons=[SP, SU], habitats=["CANOPY", "WOODLAND", "PARK"],
    stages=["larval galleries", "D-shaped exit holes", "adult"],
    links=[link("HOST_PLANT", "white_ash", "Identify and log the ash tree it is attacking")], visual=IV + ["beetle"])
add("spotted_lanternfly", "Spotted Lanternfly", "Lycorma delicatula", "INSECT", "🪲",
    "A spotted grey planthopper with startling red hindwings.",
    "It lays putty-like egg masses on anything — cars, stone, pallets. Reports of egg masses in new areas matter most.",
    native=["AS"], invasive=["NA"], seasons=YEAR, habitats=["CANOPY", "PARK", "GARDEN"],
    stages=["egg mass", "black nymph", "red nymph", "adult"],
    links=[link("HOST_PLANT", "tree_of_heaven", "Find its favourite host tree nearby")], visual=IV)
add("garden_spider", "European Garden Spider", "Araneus diadematus", "INSECT", "🕷",
    "The cross-backed orb weaver of autumn hedges.",
    "It eats and re-spins its web most nights, recycling the silk protein.",
    native=["EU", "AS"], intro=["NA"], seasons=[SU, AU], habitats=["GARDEN", "PARK", "SCRUB"], stages=["spiderlings", "adult", "egg sac"],
    links=[link("EATS", "group:INSECT", "Record what flies into its web")], visual=IV + ["spider"])

# ───────────── Other invertebrates ─────────────
add("common_earthworm", "Common Earthworm", "Lumbricus terrestris", "OTHER", "🪱",
    "The deep-burrowing nightcrawler that drags leaves underground.",
    "Darwin spent forty years on it. A gardener's friend in Europe — but in once-glaciated North American forests it strips the leaf litter wildflowers need.",
    native=["EU"], intro=["AS", "OC", "SA"], invasive=["NA"], habitats=["GARDEN", "PARK", "WOODLAND"], stages=["cocoon", "adult", "casts & middens"],
    links=[link("DECOMPOSES", "group:TREE", "Whose fallen leaves is it pulling under?"),
           link("EATEN_BY", ["american_robin", "common_blackbird", "european_robin", "group:BIRD"], "Find a bird hunting it")],
    visual=["worm", "invertebrate"])

# ───────────── Amphibians ─────────────
AV = ["frog", "amphibian", "toad"]
FROG_ST = ["spawn", "tadpole", "froglet", "adult"]
add("american_bullfrog", "American Bullfrog", "Lithobates catesbeianus", "AMPHIBIAN", "🐸",
    "A dinner-plate frog with a foghorn voice.",
    "Farmed worldwide for its legs, escapees now eat native frogs and spread a lethal amphibian fungus.",
    native=["NA"], invasive=["EU", "SA", "AS"], seasons=[SP, SU, AU], habitats=["WATER", "WETLAND"], stages=FROG_ST,
    links=[link("SHELTERS_IN", ["habitat:WATER", "habitat:WETLAND"], "Log the permanent pond it needs"),
           link("EATS", ["group:INSECT", "group:AMPHIBIAN"], "It eats anything it can swallow — what lives alongside it?")],
    acoustic=call(150, 600, 0.5, 2, True, "Deep, resonant jug-o-rum, jug-o-rum"), visual=AV)
add("spring_peeper", "Spring Peeper", "Pseudacris crucifer", "AMPHIBIAN", "🐸",
    "A thumbnail-sized frog whose chorus is the sound of spring.",
    "It survives winter partly frozen, its cells protected by glucose antifreeze, and sings from pools too short-lived for fish.",
    native=["NA"], seasons=[SP], habitats=["WETLAND", "WOODLAND"], stages=FROG_ST,
    links=[link("SHELTERS_IN", ["habitat:WETLAND", "habitat:WATER"], "Log the temporary pool it breeds in"),
           link("SHELTERS_IN", "group:TREE", "Outside spring it lives in woodland — record the trees")],
    acoustic=call(2600, 3500, 1, 2.5, True, "A piercing, rising peep, repeated — sleigh-bells en masse"), visual=AV)
add("common_frog", "Common Frog", "Rana temporaria", "AMPHIBIAN", "🐸",
    "The smooth-skinned garden frog with a dark eye-mask.",
    "A garden pond without fish is among the most valuable things one household can give wildlife.",
    native=["EU", "AS"], seasons=[SP, SU, AU], habitats=["GARDEN", "WETLAND", "WATER"], stages=FROG_ST,
    links=[link("SHELTERS_IN", ["habitat:WATER", "habitat:WETLAND", "habitat:GARDEN"], "Log the pond where it spawns"),
           link("EATS", ["common_earthworm", "group:INSECT"], "It hunts slugs and insects on damp nights")],
    acoustic=call(300, 1500, 6, 14, False, "A low, purring croak, like a distant motorbike"), visual=AV)
add("asian_common_toad", "Asian Common Toad", "Duttaphrynus melanostictus", "AMPHIBIAN", "🐸",
    "A warty, black-crested toad under every monsoon streetlight.",
    "It gathers under lamps to eat the insects they attract. Introduced to Madagascar, it now threatens predators with no defence against its toxins.",
    native=["AS"], invasive=["AF", "OC"], seasons=[SP, SU, AU], habitats=["GARDEN", "WETLAND", "PARK"], stages=["spawn strings", "tadpole", "toadlet", "adult"],
    links=[link("EATS", "group:INSECT", "Record the insects it gathers to eat"),
           link("SHELTERS_IN", ["habitat:WATER", "habitat:WETLAND", "habitat:GARDEN"], "Log the rain pool where it breeds")],
    acoustic=call(1000, 2000, 8, 18, False, "A long, rattling creo-o-o, cro-ro-ro"), visual=AV)
add("cane_toad", "Cane Toad", "Rhinella marina", "AMPHIBIAN", "🐸",
    "A huge toad released to save sugar cane — which it did not.",
    "Its toxin kills the quolls, snakes and goannas that try to eat it. Records at the invasion front guide control.",
    native=["SA", "NA"], invasive=["OC", "AS"], habitats=["GARDEN", "WETLAND", "MEADOW"], stages=["spawn strings", "tadpole", "toadlet", "adult"],
    links=[link("SHELTERS_IN", ["habitat:WATER", "habitat:WETLAND"], "Log the water body it is breeding in")],
    acoustic=call(400, 900, 9, 18, False, "A low, purring trill like a distant idling engine"), visual=AV)
add("spotted_salamander", "Spotted Salamander", "Ambystoma maculatum", "AMPHIBIAN", "🦎",
    "A stout black salamander with yellow polka dots, seen on one rainy night a year.",
    "Its embryos host algae inside their own cells — the only known vertebrate to do so. Breeding pools are few and easily destroyed.",
    native=["NA"], sensitive=True, seasons=[SP], habitats=["WOODLAND", "WETLAND"], stages=["egg mass", "larva", "adult"],
    links=[link("SHELTERS_IN", ["habitat:WETLAND", "habitat:WATER"], "Log the fishless vernal pool"),
           link("SHELTERS_IN", "group:TREE", "It spends the year under woodland logs — record the trees")],
    visual=AV + ["salamander", "lizard", "reptile"])

# ───────────── Reptiles ─────────────
RV = ["reptile", "lizard", "snake"]
add("common_garter_snake", "Common Garter Snake", "Thamnophis sirtalis", "REPTILE", "🐍",
    "A striped, harmless snake of gardens and pond edges.",
    "It can eat toxic newts and toads that would kill other predators, and overwinters in communal dens of hundreds.",
    native=["NA"], seasons=[SP, SU, AU], habitats=["GARDEN", "WETLAND", "MEADOW"], stages=["juvenile", "adult", "shed skin"],
    phenos=[ph("melanistic", "Melanistic", "Entirely black"), ph("red_sided", "Red-sided", "Red bars between the stripes")],
    links=[link("EATS", ["common_earthworm", "group:AMPHIBIAN"], "Record the worms or frogs it hunts"),
           link("SHELTERS_IN", ["habitat:WETLAND", "habitat:MEADOW", "habitat:GARDEN"], "Log the damp, grassy ground it needs")], visual=RV)
add("oriental_garden_lizard", "Oriental Garden Lizard", "Calotes versicolor", "REPTILE", "🦎",
    "A crested hedge lizard — the 'bloodsucker' that sucks no blood.",
    "Breeding males flush crimson on the head and throat within minutes, then fade again.",
    native=["AS"], intro=["AF", "NA"], habitats=["GARDEN", "SCRUB", "PARK"], stages=["hatchling", "adult"],
    phenos=[ph("breeding_male", "Breeding colours", "Head and throat flushed bright red")],
    links=[link("EATS", "group:INSECT", "Record the insects it ambushes"),
           link("SHELTERS_IN", "group:TREE", "Find the shrub or trunk it displays from")], visual=RV)
add("common_house_gecko", "Common House Gecko", "Hemidactylus frenatus", "REPTILE", "🦎",
    "The chirping wall-walker beside every porch light.",
    "Its toes grip with millions of microscopic hairs, no glue required. It has ridden ships to nearly every warm port.",
    native=["AS"], intro=["OC", "AF", "NA", "SA"], habitats=["GARDEN", "CANOPY", "PARK"], stages=["egg", "juvenile", "adult"],
    links=[link("EATS", "group:INSECT", "Record the moths and flies it hunts at the light")],
    acoustic=call(1500, 4500, 4, 8, False, "A sharp chuck-chuck-chuck-chuck"), visual=RV + ["gecko"])

# ───────────── Mammals ─────────────
MV = ["mammal", "squirrel", "gerbil", "fur"]
SIGNS = ["tracks & signs", "juvenile", "adult"]
add("eastern_gray_squirrel", "Eastern Gray Squirrel", "Sciurus carolinensis", "MAMMAL", "🐿",
    "The acrobatic nut-burier of city parks.",
    "It forgets a share of its buried acorns — and so plants forests. In Britain and Italy it is displacing the native red squirrel.",
    native=["NA"], intro=["AF"], invasive=["EU"], habitats=["PARK", "WOODLAND", "CANOPY", "GARDEN"], stages=["drey (nest)"] + SIGNS,
    phenos=[ph("melanistic", "Black morph", "Jet-black coat, commoner in northern cities"),
            ph("white", "White morph", "Leucistic or albino — a local celebrity")],
    links=[link("DISPERSES", ["white_oak", "english_oak", "group:TREE"], "Find the tree whose seeds it plants"),
           link("EATEN_BY", ["red_tailed_hawk", "great_horned_owl", "group:BIRD"], "What watches it from above?")], visual=MV)
add("red_squirrel", "Eurasian Red Squirrel", "Sciurus vulgaris", "MAMMAL", "🐿",
    "The tuft-eared native squirrel of Eurasian woods.",
    "It survives where conifers or pine martens give it an edge over the introduced grey. Each record helps map its retreat — or return.",
    native=["EU", "AS"], habitats=["WOODLAND", "PARK"], stages=["drey (nest)", "chewed cones"] + SIGNS,
    links=[link("SHELTERS_IN", "group:TREE", "Identify the trees it depends on"),
           link("COMPETES_WITH", "eastern_gray_squirrel", "Is its rival present here?")], visual=MV)
add("indian_palm_squirrel", "Three-striped Palm Squirrel", "Funambulus palmarum", "MAMMAL", "🐿",
    "A chirruping, striped squirrel of gardens and temple walls.",
    "A busy pollinator and seed-carrier of garden trees; its bird-like alarm call warns the whole neighbourhood.",
    native=["AS"], intro=["OC"], habitats=["GARDEN", "PARK", "CANOPY"], stages=["nest", "juvenile", "adult"],
    links=[link("DISPERSES", ["neem", "banyan", "group:TREE"], "Find the fruiting tree it raids")],
    acoustic=call(3000, 6500, 2, 5, True, "A sharp, repeated bird-like chip-chip-chip"), visual=MV)
add("european_hedgehog", "European Hedgehog", "Erinaceus europaeus", "MAMMAL", "🦔",
    "A snuffling night gardener in sharp decline.",
    "One hedgehog roams a mile a night. A 13 cm gap under a fence links gardens into the territory it needs.",
    native=["EU"], invasive=["OC"], seasons=[SP, SU, AU], habitats=["GARDEN", "PARK", "MEADOW"], stages=["droppings & tracks", "hoglet", "adult"],
    links=[link("EATS", ["common_earthworm", "group:INSECT"], "Record the beetles and worms it forages"),
           link("SHELTERS_IN", ["habitat:GARDEN", "habitat:PARK"], "Log the connected, untidy garden it needs")], visual=["mammal", "hedgehog"])
add("red_fox", "Red Fox", "Vulpes vulpes", "MAMMAL", "🦊",
    "The most widespread wild carnivore on Earth.",
    "It thrives in cities on rodents, worms and fallen fruit. In Australia, introduced foxes have driven small marsupials extinct.",
    native=["NA", "EU", "AS", "AF"], invasive=["OC"], habitats=["PARK", "MEADOW", "WOODLAND", "GARDEN"], stages=["tracks & scat", "cub", "adult"],
    links=[link("EATS", ["common_earthworm", "group:MAMMAL"], "Record its prey — rodents, rabbits, even worms"),
           link("SHELTERS_IN", ["habitat:WOODLAND", "habitat:SCRUB", "habitat:PARK"], "Log the cover where it dens")],
    acoustic=call(700, 2500, 0.3, 1.5, False, "A hoarse triple bark, or a blood-curdling winter scream"), visual=["mammal", "fox", "dog"])
add("common_pipistrelle", "Common Pipistrelle", "Pipistrellus pipistrellus", "MAMMAL", "🦇",
    "A thumb-sized bat flickering round treetops at dusk.",
    "It eats up to three thousand midges a night. Its calls are far above human hearing, so log it by sight at dusk.",
    native=["EU", "AS", "AF"], seasons=[SP, SU, AU], habitats=["WATER", "PARK", "WOODLAND", "GARDEN"], stages=["in flight", "roost"],
    links=[link("EATS", "group:INSECT", "Record the insects that are flying where it hunts"),
           link("SHELTERS_IN", ["habitat:WATER", "group:TREE"], "It follows tree lines to water — log the route")], visual=["mammal", "bat"])
add("big_brown_bat", "Big Brown Bat", "Eptesicus fuscus", "MAMMAL", "🦇",
    "A sturdy, glossy bat of barns, attics and streetlights.",
    "A colony eats enough beetles each summer to spare farmers millions of crop-pest larvae. Its calls are ultrasonic — log it by sight.",
    native=["NA", "SA"], seasons=[SP, SU, AU], habitats=["PARK", "WATER", "WOODLAND", "GARDEN"], stages=["in flight", "roost"],
    links=[link("EATS", "group:INSECT", "Record the insects flying where it hunts"),
           link("SHELTERS_IN", ["habitat:WATER", "group:TREE"], "It needs old trees and open water")], visual=["mammal", "bat"])
add("indian_flying_fox", "Indian Flying Fox", "Pteropus medius", "MAMMAL", "🦇",
    "A fox-faced fruit bat with a wingspan over a metre.",
    "Roost trees are used for generations and carry seeds for tens of kilometres each night. Camps are easily disturbed, so their locations are obscured.",
    native=["AS"], sensitive=True, habitats=["CANOPY", "PARK", "WOODLAND"], stages=["in flight", "roost camp"],
    links=[link("DISPERSES", ["banyan", "neem", "group:TREE"], "Find the fruiting trees it visits by night"),
           link("SHELTERS_IN", "group:TREE", "Record the kind of tree the camp hangs in")],
    acoustic=call(1000, 5000, 2, 6, False, "Squabbling shrieks and chatter from the roost"), visual=["mammal", "bat"])
add("white_tailed_deer", "White-tailed Deer", "Odocoileus virginianus", "MAMMAL", "🦌",
    "A flag-tailed browser, most often known by its tracks.",
    "With wolves gone, dense deer herds eat forests bare of seedlings and wildflowers — a browse line at head height is their signature.",
    native=["NA", "SA"], habitats=["WOODLAND", "MEADOW", "PARK"], stages=["tracks & signs", "fawn", "doe", "buck"],
    phenos=[ph("piebald", "Piebald", "Blotched white and brown"), ph("velvet", "In velvet", "Summer antlers sheathed in fuzzy skin")],
    links=[link("EATS", ["white_trillium", "group:PLANT"], "Record the wildflowers and saplings it browses"),
           link("SHELTERS_IN", "habitat:WOODLAND", "Log it at the woodland edge")], visual=["mammal", "deer"])

# ───────────── validate & write ─────────────
ids = [s["id"] for s in species]
assert len(ids) == len(set(ids)), "duplicate ids"
GROUPS = {"BIRD", "PLANT", "TREE", "FUNGUS", "INSECT", "AMPHIBIAN", "REPTILE", "MAMMAL", "OTHER"}
HABS = {"PARK", "WOODLAND", "WETLAND", "WATER", "MEADOW", "GARDEN", "CANOPY", "SCRUB"}
for s in species:
    assert s["group"] in GROUPS, s["id"]
    assert s["nativeTo"], s["id"]
    for h in s["habitats"]:
        assert h in HABS, (s["id"], h)
    for l in s["links"]:
        for t in l["targets"]:
            if t.startswith("group:"):
                assert t[6:] in GROUPS, (s["id"], t)
            elif t.startswith("habitat:"):
                assert t[8:] in HABS, (s["id"], t)
            else:
                assert t in ids, (s["id"], t)
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "catalog.json")
with open(out, "w", encoding="utf-8") as f:
    json.dump(species, f, ensure_ascii=False, indent=1)
print(f"wrote {len(species)} species")
