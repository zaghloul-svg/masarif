package com.zaghloul.masarif.data

/**
 * Starter merchant -> category mappings for the UAE.
 *
 * Every entry is an editable row in `merchant_rule`, not hard-coded logic: rename
 * or delete any of it from Settings. Keywords are matched whole-word against the
 * normalized merchant name, so short ones like "DU" never match inside a longer word.
 *
 * Deliberately absent: seeded regex parse rules for specific banks. Guessing a
 * bank's exact alert template produces a parser that breaks on the first wording
 * change. The heuristic extractor in ParseEngine handles the general case, and the
 * Rule Lab builds an exact rule from a real message when you want one.
 */
object SeedData {

    fun merchantRules(): List<Pair<String, String>> = buildList {
        fun add(category: String, vararg keywords: String) {
            keywords.forEach { add(it to category) }
        }

        add(
            Cat.GROCERIES,
            "CARREFOUR", "LULU HYPERMARKET", "LULU MARKET", "SPINNEYS", "UNION COOP",
            "WAITROSE", "CHOITHRAMS", "GEANT", "VIVA SUPERMARKET", "ZOOM", "AL MAYA",
            "NESTO", "WEST ZONE", "ALL DAY", "GRANDIOSE", "MEGAMART", "BAQALA",
            "TALABAT MART", "INSTASHOP", "KIBSONS", "SUPERMARKET", "HYPERMARKET", "GROCERY"
        )

        add(
            Cat.DINING,
            "TALABAT", "DELIVEROO", "CAREEM FOOD", "NOON FOOD", "ZOMATO", "UBER EATS",
            "SMILES", "MCDONALDS", "MCDONALD S", "KFC", "STARBUCKS", "COSTA COFFEE",
            "TIM HORTONS", "SUBWAY", "PIZZA HUT", "DOMINOS", "BURGER KING", "SHAKE SHACK",
            "FIVE GUYS", "CARIBOU", "DUNKIN", "BASKIN ROBBINS", "POPEYES", "HARDEES",
            "PAPA JOHNS", "WAGAMAMA", "NANDOS", "ALLO BEIRUT", "OPERATION FALAFEL",
            "ZAATAR W ZEIT", "TEXAS CHICKEN", "JOLLIBEE", "CHIPOTLE", "PRET A MANGER",
            "RESTAURANT", "CAFE", "COFFEE", "SHAWARMA", "BAKERY", "GRILL", "BISTRO", "KITCHEN"
        )

        add(
            Cat.TRANSPORT,
            "ADNOC", "ENOC", "EPPCO", "EMARAT", "SALIK", "RTA", "CAREEM", "UBER",
            "DUBAI TAXI", "PARKIN", "PARKING", "DARB", "NOL", "METRO", "PETROL",
            "CAR WASH", "TASJEEL", "SHAMIL", "MUROOR", "TAMM", "AUTOPRO", "ZDEGREE"
        )

        add(
            Cat.UTILITIES,
            "DEWA", "SEWA", "FEWA", "ADDC", "AADC", "ETISALAT", "EAND", "DU",
            "VIRGIN MOBILE", "EMPOWER", "TABREED", "TASHEEL", "DU TELECOM"
        )

        add(
            Cat.SHOPPING,
            "NOON", "AMAZON AE", "AMAZON", "NAMSHI", "SHEIN", "IKEA", "HOME CENTRE",
            "ACE HARDWARE", "CENTREPOINT", "MAX FASHION", "SPLASH", "BABYSHOP", "H M",
            "ZARA", "MARKS SPENCER", "DEBENHAMS", "SHARAF DG", "JUMBO", "EMAX",
            "VIRGIN MEGASTORE", "APPLE STORE", "SAMSUNG", "DECATHLON", "MUJI", "DAISO",
            "MINISO", "LULU CONNECT", "JACKY S", "ALIEXPRESS", "TEMU", "AJMAL", "BATH BODY",
            "SEPHORA", "NAJEEB", "DRAGON MART", "BRANDS FOR LESS", "REDTAG"
        )

        add(
            Cat.HEALTH,
            "ASTER", "LIFE PHARMACY", "BOOTS", "BINSINA", "BIN SINA", "SUPERCARE",
            "MEDCARE", "NMC", "MEDICLINIC", "AMERICAN HOSPITAL", "SAUDI GERMAN",
            "THUMBAY", "PRIME HOSPITAL", "ZULEKHA", "BURJEEL", "CLEVELAND CLINIC",
            "PHARMACY", "CLINIC", "HOSPITAL", "DENTAL", "OPTICAL", "MEDICAL", "LABS"
        )

        add(
            Cat.ENTERTAINMENT,
            "VOX CINEMAS", "REEL CINEMAS", "ROXY CINEMAS", "NOVO CINEMAS", "CINEMA",
            "IMG WORLDS", "MOTIONGATE", "LEGOLAND", "FERRARI WORLD", "YAS WATERWORLD",
            "WARNER BROS", "SEAWORLD", "GLOBAL VILLAGE", "SKI DUBAI", "AQUAVENTURE",
            "DUBAI PARKS", "BOWLING", "PLAYSTATION", "XBOX", "STEAM GAMES", "NINTENDO",
            "TICKETMASTER", "PLATINUMLIST", "DUBAI FRAME", "BURJ KHALIFA"
        )

        add(
            Cat.SUBSCRIPTIONS,
            "NETFLIX", "SPOTIFY", "SHAHID", "OSN", "STARZPLAY", "ANGHAMI", "YOUTUBE",
            "APPLE COM BILL", "ITUNES", "ICLOUD", "GOOGLE", "MICROSOFT", "ADOBE",
            "CANVA", "CHATGPT", "OPENAI", "ANTHROPIC", "CLAUDE AI", "NOTION", "DROPBOX",
            "LINKEDIN", "AMAZON PRIME", "DISNEY", "DAZN", "AUDIBLE", "PATREON",
            "GITHUB", "FIGMA", "SLACK", "ZOOM US"
        )

        add(
            Cat.TRAVEL,
            "EMIRATES", "FLYDUBAI", "ETIHAD AIRWAYS", "AIR ARABIA", "WIZZ AIR",
            "QATAR AIRWAYS", "BOOKING COM", "AGODA", "AIRBNB", "EXPEDIA", "ALMOSAFER",
            "WEGO", "SKYSCANNER", "HOTEL", "RESORT", "TRAVEL", "TOURS", "AIRPORT",
            "DUTY FREE", "RENT A CAR", "HERTZ", "AVIS", "BUDGET RENT"
        )

        add(
            Cat.CASH,
            "ATM", "CASH WITHDRAWAL", "WESTERN UNION", "AL ANSARI", "AL FARDAN",
            "LULU EXCHANGE", "UAE EXCHANGE", "WALL STREET EXCHANGE", "INDEX EXCHANGE",
            "REDHA AL ANSARI", "SHARAF EXCHANGE", "REMIT", "MONEY TRANSFER", "TRANSFER",
            "WISE", "PAYPAL"
        )

        add(
            Cat.BILLS,
            "EJARI", "RENT", "TENANCY", "DUBAI MUNICIPALITY", "DAMAC", "EMAAR",
            "ALDAR", "DEYAAR", "RERA", "NAKHEEL", "LANDLORD", "INSURANCE", "DAMAN",
            "ORIENT INSURANCE", "SALAMA", "TAKAFUL"
        )

        add(
            Cat.EDUCATION,
            "UNIVERSITY", "SCHOOL", "UDEMY", "COURSERA", "KHAN ACADEMY", "TUITION",
            "ACADEMY", "INSTITUTE", "KHDA", "NURSERY", "TRAINING"
        )

        add(
            Cat.FEES,
            "ANNUAL FEE", "LATE PAYMENT", "OVERLIMIT", "SERVICE CHARGE", "FX FEE",
            "MARKUP FEE", "INTEREST CHARGE", "FINANCE CHARGE", "CARD FEE", "VAT ON"
        )

        add(Cat.INCOME, "SALARY", "PAYROLL", "REFUND")
    }
}
