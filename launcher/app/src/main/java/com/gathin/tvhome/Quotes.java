package com.gathin.tvhome;

/**
 * The quote card's list, bundled (no network) so every text and source is checked once, here.
 * Qur'an lines are the Kemenag RI Indonesian translation, shortened to the well-known part of the verse;
 * hadith are from the Bukhari/Muslim/Tirmidzi collections as commonly cited in Indonesia.
 * Interleaved by kind, so consecutive quotes differ. Edit freely: {kind, text, source}.
 */
final class Quotes {
    private Quotes() { }

    static final String QURAN = "Al-Qur'an", HADITH = "Hadis", ID = "Kutipan", EN = "Quote";

    static final String[][] ALL = {
            {QURAN, "Maka sesungguhnya beserta kesulitan ada kemudahan. Sesungguhnya beserta kesulitan ada kemudahan.", "QS. Al-Insyirah 94: 5–6"},
            {EN, "Life is like riding a bicycle. To keep your balance, you must keep moving.", "Albert Einstein"},
            {HADITH, "Sesungguhnya setiap amal tergantung pada niatnya.", "HR. Bukhari & Muslim"},
            {ID, "Habis gelap terbitlah terang.", "R.A. Kartini"},
            {QURAN, "Allah tidak membebani seseorang melainkan sesuai dengan kesanggupannya.", "QS. Al-Baqarah 2: 286"},
            {EN, "Stay hungry, stay foolish.", "Steve Jobs"},
            {HADITH, "Barangsiapa beriman kepada Allah dan hari akhir, hendaklah ia berkata baik atau diam.", "HR. Bukhari & Muslim"},
            {ID, "Jas merah: jangan sekali-kali meninggalkan sejarah.", "Soekarno"},
            {QURAN, "Ingatlah, hanya dengan mengingat Allah hati menjadi tenteram.", "QS. Ar-Ra'd 13: 28"},
            {EN, "The only way to do great work is to love what you do.", "Steve Jobs"},
            {HADITH, "Orang yang kuat bukanlah yang pandai bergulat, tetapi yang mampu menahan dirinya ketika marah.", "HR. Bukhari & Muslim"},
            {ID, "Ing ngarsa sung tuladha, ing madya mangun karsa, tut wuri handayani.", "Ki Hajar Dewantara"},
            {QURAN, "Mohonlah pertolongan (kepada Allah) dengan sabar dan salat. Sungguh, Allah beserta orang-orang yang sabar.", "QS. Al-Baqarah 2: 153"},
            {EN, "We are what we repeatedly do. Excellence, then, is not an act, but a habit.", "Will Durant"},
            {HADITH, "Amalan yang paling dicintai Allah adalah yang terus-menerus, walaupun sedikit.", "HR. Bukhari & Muslim"},
            {ID, "Setiap orang menjadi guru, setiap rumah menjadi sekolah.", "Ki Hajar Dewantara"},
            {QURAN, "Sesungguhnya Allah tidak akan mengubah keadaan suatu kaum sebelum mereka mengubah keadaan diri mereka sendiri.", "QS. Ar-Ra'd 13: 11"},
            {EN, "Imagination is more important than knowledge.", "Albert Einstein"},
            {HADITH, "Tidak sempurna iman seseorang di antara kalian hingga ia mencintai saudaranya sebagaimana ia mencintai dirinya sendiri.", "HR. Bukhari & Muslim"},
            {ID, "Orang boleh pandai setinggi langit, tapi selama ia tidak menulis, ia akan hilang di dalam masyarakat dan dari sejarah.", "Pramoedya Ananta Toer"},
            {QURAN, "Sesungguhnya jika kamu bersyukur, niscaya Aku akan menambah (nikmat) kepadamu.", "QS. Ibrahim 14: 7"},
            {EN, "Talk is cheap. Show me the code.", "Linus Torvalds"},
            {HADITH, "Senyummu di hadapan saudaramu adalah sedekah.", "HR. Tirmidzi"},
            {ID, "Bermimpilah, karena Tuhan akan memeluk mimpi-mimpi itu.", "Andrea Hirata"},
            {QURAN, "Barangsiapa bertawakal kepada Allah, niscaya Allah akan mencukupkan (keperluan)nya.", "QS. At-Talaq 65: 3"},
            {EN, "The best time to plant a tree was 20 years ago. The second best time is now.", "Proverb"},
            {HADITH, "Permudahlah dan jangan mempersulit, berilah kabar gembira dan jangan membuat orang lari.", "HR. Bukhari & Muslim"},
            {ID, "Man jadda wajada: siapa yang bersungguh-sungguh, pasti berhasil.", "Pepatah Arab"},
            {QURAN, "Berdoalah kepada-Ku, niscaya akan Aku perkenankan bagimu.", "QS. Ghafir 40: 60"},
            {EN, "Premature optimization is the root of all evil.", "Donald Knuth"},
            {HADITH, "Bersuci adalah sebagian dari iman.", "HR. Muslim"},
            {QURAN, "Janganlah kamu berputus asa dari rahmat Allah.", "QS. Az-Zumar 39: 53"},
            {QURAN, "Maka ingatlah kepada-Ku, Aku pun akan ingat kepadamu.", "QS. Al-Baqarah 2: 152"},
    };

    /** The quote for this moment: changes every `minutes`, same one across restarts within that slot. */
    static String[] at(long epochMillis, int minutes) {
        long slot = epochMillis / (Math.max(1, minutes) * 60_000L);
        return ALL[(int) (slot % ALL.length)];
    }

    /** Chip colour per kind. */
    static int color(String kind) {
        switch (kind) {
            case QURAN: return 0xFF1E9E6A;
            case HADITH: return 0xFF14868C;
            case ID: return 0xFFC0392B;
            default: return 0xFFB7791F;
        }
    }
}
