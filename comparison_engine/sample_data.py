"""Sample Comparison Datasets."""

from .models import ComparisonItem, TimelineProject, CreditsInfo, VideoConfig

def get_evolution_of_language_project() -> TimelineProject:
    """Returns the full 'Evolution of Language' comparison timeline."""
    raw_data = [
        ("7M", "YEARS AGO", "Ape Noises And Gestures", "Our chimp ancestors spoke with hoots and gestures"),
        ("400K", "YEARS AGO", "Language Section Of Brain Develops", "The FOXP2 gene gave us the language part of our brain"),
        ("300K", "YEARS AGO", "Voice Box Evolves Fully", "A lower larynx unlocked many more distinct sounds"),
        ("70K", "YEARS AGO", "Clicking Languages", "Some languages like Khoisan still use clicking sounds"),
        ("8000", "BC", "Trade Language", "Clay tokens counted goods: writing grew from accounting"),
        ("6600", "BC", "First Written Symbols", "Chinese Jiahu might be a primitive form of writing"),
        ("6000", "BC", "F And V Sounds Appear", "Soft farming diets gave us an overbite, easing 'F', 'V'"),
        ("4000", "BC", "Proto-Indo-European Develops", "Many languages, from English to Hindi, developed from this"),
        ("3400", "BC", "First Real Writing", "Sumerian cuneiform began, at first just for receipts"),
        ("3100", "BC", "People Start Writing Left-To-Right", "Older languages write right-to-left"),
        ("1050", "BC", "First Alphabet", "Phoenicians slimmed writing to 22 letters, no vowels"),
        ("800", "BC", "First Written Vowels", "Greeks add A, E, I, O, U to their alphabet"),
        ("700", "BC", "Latin Alphabet (Modern Letters)", "Romans reshape Greek into the ABCs we still type"),
        ("300", "BC", "Tamil Is First Spoken", "The oldest language still spoken every day"),
        ("200", "BC", "Punctuation Invented", "A Greek librarian invents dots for pauses"),
        ("105", "AD", "Paper Is Invented", "China's Cai Lun makes writing cheap and portable"),
        ("450", "AD", "Old English Emerges", "Anglo-Saxons brought Germanic elements over"),
        ("500", "AD", "Modern Numbers Invented", "Hindu-Arabic numerals are still used today: 0 to 9"),
        ("700", "AD", "Adding Spaces Between Words", "Irish monks add gaps, making text readable"),
        ("800", "AD", "Lowercase Letters Invented", "Before this, all writing was capital letters!"),
        ("800", "AD", "Question Mark Invented", "Medieval scribes mark a query with a curl"),
        ("1066", "AD", "French Merges With English", "Normans add pork, beef, justice and parliament"),
        ("1300", "AD", "Modern Italian Emerges", "Dante writes his Comedy, shaping the Italian used today"),
        ("1400", "AD", "Exclamation Mark Invented", "It was originally written 'io', turning into an i above an O!"),
        ("1440", "AD", "The Printing Press Is Invented", "Gutenberg freezes spelling and spreads literacy"),
        ("1492", "AD", "Modern Spanish", "Castilian gets its first grammar as Spain unites"),
        ("1522", "AD", "Modern German Emerges", "Luther's printed Bible sets the standard for German"),
        ("1524", "AD", "Newest Letter: The 'J'", "English's youngest letter splits off from I"),
        ("1539", "AD", "Modern French Emerges", "A royal decree makes French the language of law"),
        ("1550", "AD", "Modern English", "A huge vowel shift turns Middle English into ours"),
        ("1604", "AD", "First English Dictionary", "This locked in the spellings for many words"),
        ("1755", "AD", "Modern Russian Emerges", "Lomonosov's grammar fuses old Slavonic with real speech"),
        ("1760", "AD", "Sign Language Invented", "A Paris deaf school turns gestures into language"),
        ("1822", "AD", "Hieroglyphs Decoded", "After 3,000 years, Ancient Egyptian can be read again"),
        ("1824", "AD", "Braille Invented", "A blind teenager turns reading into touch"),
        ("1828", "AD", "American Spelling Splits Off", "Webster drops the U from words like 'colour'"),
        ("1844", "AD", "Morse Code Invented", "Language shrinks to dots and dashes by wire"),
        ("1868", "AD", "Typewriter Invented", "Mechanised writing gave us the QWERTY keyboard"),
        ("1876", "AD", "Telephone First Used", "Live voices cross great distances at last"),
        ("1887", "AD", "Esperanto Is Developed", "It was meant to be a universal language, for world peace"),
        ("1900", "AD", "Hebrew Is Revived", "The only dead tongue brought back to daily life"),
        ("1954", "AD", "Machine Translation Begins", "A computer auto-translates Russian into English"),
        ("1957", "AD", "Programming Languages", "Humans write new languages to command machines"),
        ("1982", "AD", "First Emoticon", "Fahlman's :-) flags a joke from earnest text"),
        ("1991", "AD", "Unicode Invented", "One code holds every script on Earth"),
        ("1995", "AD", "Predictive Text & Autocorrect", "Phones begin guessing words from few keypresses"),
        ("1999", "AD", "First Emoji Used", "Kurita draws 176 icons for tiny phone screens"),
        ("2000", "AD", "Textspeak Explodes", "'OMG' spread fast, yet dates to 1917"),
        ("2004", "AD", "Social Media", "Billions write publicly, coining new slang daily"),
        ("2006", "AD", "Google Translate Launches", "This helps bridge the language barrier for the average person"),
        ("2015", "AD", "An Emoji Wins 'Word Of The Year'", "Oxford picks the first pictograph ever to win"),
        ("2022", "AD", "AI Masters Language", "ChatGPT writes fluently: a huge shift like printing"),
        ("2025", "AD", "YouTube Adds AutoDub", "A global audience can enjoy the same videos equally"),
        ("2030", "AD", "Live AR Translation", "Smart glasses will subtitle any conversation in real time"),
        ("2075", "AD", "Brain-To-Brain Messaging", "Implants may send thoughts directly, skipping words"),
        ("2100", "AD", "90% Of Languages Die", "Linguists predict half of 7,000 languages vanish"),
        ("2200", "AD", "One Global Language", "Humanity may converge on a single shared tongue")
    ]
    
    items = [
        ComparisonItem(badge_value=v, badge_unit=u, title=t, description=d)
        for v, u, t, d in raw_data
    ]
    
    return TimelineProject(
        title="Comparison: Evolution Of Language",
        items=items,
        credits=CreditsInfo(),
        config=VideoConfig(
            width=1920,
            height=1080,
            fps=60,
            scroll_speed_px_per_sec=160.0,
            intro_duration_sec=3.0,
            outro_duration_sec=3.5,
            output_path="output/evolution_of_language.mp4"
        )
    )
