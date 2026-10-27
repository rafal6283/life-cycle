package pl.rafal.koofr;

/** Błąd kopii/podglądu z komunikatem gotowym do pokazania użytkownikowi. */
public class KsException extends Exception {
    public final int status;

    public KsException(String message) {
        this(message, 0);
    }

    public KsException(String message, int status) {
        super(message);
        this.status = status;
    }

    static KsException http(int status, String what) {
        String m;
        if (status == 401) m = "Koofr odrzucił logowanie — sprawdź e-mail i hasło aplikacji.";
        else if (status == 403) m = "Brak dostępu do folderu na Koofr.";
        else if (status == 404) m = "Nie znaleziono: " + what;
        else if (status == 423) m = "Plik jest zablokowany na Koofr, spróbuj za chwilę.";
        else if (status == 507) m = "Brak wolnego miejsca na koncie Koofr.";
        else if (status >= 500) m = "Koofr ma chwilowe problemy (" + status + "), spróbuj później.";
        else m = "Błąd Koofr " + status + " przy: " + what;
        return new KsException(m, status);
    }
}
