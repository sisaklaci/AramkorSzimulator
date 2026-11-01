import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.Preferences;

/** Nyelv-azonosítók: az enum sorrendje rögzíti az oszlopok sorrendjét a fordítási táblában. */
enum Lang {
    HU("Magyar"), EN("English"), DE("Deutsch"), FR("Français"), ES("Español"), PL("Polski");

    final String nev;

    Lang(String nev) { this.nev = nev; }
}

/** Egyszerű kulcs alapú fordítási tár, futásidőben váltható nyelvvel, elmentve a következő induláshoz. */
final class I18n {
    private I18n() { }

    static Lang nyelv = betoltottNyelv();

    private static Lang betoltottNyelv() {
        try {
            return Lang.valueOf(Preferences.userNodeForPackage(I18n.class).get("nyelv", Lang.HU.name()));
        } catch (Exception ignored) {
            return Lang.HU;
        }
    }

    static void valt(Lang uj) {
        nyelv = uj;
        try {
            Preferences.userNodeForPackage(I18n.class).put("nyelv", uj.name());
        } catch (Exception ignored) {
            // a nyelv ettől még az aktuális futásban érvényes marad, csak nem marad meg újraindítás után
        }
    }

    /** Lefordított szöveg az aktuális nyelven; ismeretlen kulcsnál a kulcs magát adja vissza. */
    static String t(String kulcs) {
        String[] r = T.get(kulcs);
        return r == null ? kulcs : r[nyelv.ordinal()];
    }

    /** Mint t(), de %s / %d helyőrzőkkel behelyettesítve. */
    static String f(String kulcs, Object... args) {
        return String.format(t(kulcs), args);
    }

    static String sugo() {
        switch (nyelv) {
            case EN: return SUGO_EN;
            case DE: return SUGO_DE;
            case FR: return SUGO_FR;
            case ES: return SUGO_ES;
            case PL: return SUGO_PL;
            default: return SUGO_HU;
        }
    }

    // ================================================================ fordítási tábla
    // kulcs|magyar|angol|német|francia|spanyol|lengyel

    private static final String TABLA = """
            app.title|Áramkör-szimulátor|Circuit Simulator|Schaltungssimulator|Simulateur de circuit|Simulador de circuitos|Symulator obwodów

            part.wire|Vezeték|Wire|Draht|Fil|Cable|Przewód
            part.resistor|Ellenállás|Resistor|Widerstand|Résistance|Resistencia|Rezystor
            part.capacitor|Kondenzátor|Capacitor|Kondensator|Condensateur|Condensador|Kondensator
            part.inductor_full|Tekercs (induktivitás)|Inductor (coil)|Spule (Induktivität)|Bobine (inductance)|Bobina (inductancia)|Cewka (indukcyjność)
            part.inductor_short|Tekercs|Inductor|Spule|Bobine|Bobina|Cewka
            part.battery|Telep (egyenfeszültség)|Battery (DC source)|Batterie (Gleichspannung)|Pile (tension continue)|Pila (tensión continua)|Bateria (napięcie stałe)
            part.acsource|Váltakozó feszültségforrás|AC voltage source|Wechselspannungsquelle|Source de tension alternative|Fuente de tensión alterna|Źródło napięcia przemiennego
            part.currentsource|Áramforrás|Current source|Stromquelle|Source de courant|Fuente de corriente|Źródło prądowe
            part.diode|Dióda|Diode|Diode|Diode|Diodo|Dioda
            part.zener|Zener-dióda|Zener diode|Zener-Diode|Diode Zener|Diodo Zener|Dioda Zenera
            part.diode_zener_combo|Dióda / Zener-dióda|Diode / Zener diode|Diode / Zener-Diode|Diode / Diode Zener|Diodo / Diodo Zener|Dioda / Dioda Zenera
            part.switch|Kapcsoló|Switch|Schalter|Interrupteur|Interruptor|Przełącznik
            part.lamp|Izzó|Lamp|Glühlampe|Ampoule|Bombilla|Żarówka
            part.ammeter|Ampermérő|Ammeter|Amperemeter|Ampèremètre|Amperímetro|Amperomierz
            part.voltmeter|Voltmérő|Voltmeter|Voltmeter|Voltmètre|Voltímetro|Woltomierz
            part.ground_full|Föld (0 V)|Ground (0 V)|Masse (0 V)|Terre (0 V)|Tierra (0 V)|Uziemienie (0 V)
            part.ground_short|Föld|Ground|Masse|Terre|Tierra|Uziemienie
            part.led_format|LED (%s)|LED (%s)|LED (%s)|LED (%s)|LED (%s)|LED (%s)

            wave.sine|szinusz|sine|Sinus|sinus|seno|sinusoida
            wave.square|négyszög|square|Rechteck|carré|cuadrada|prostokątny
            wave.triangle|háromszög|triangle|Dreieck|triangle|triangular|trójkątny
            wave.saw|fűrész|sawtooth|Sägezahn|dents de scie|diente de sierra|piłokształtny

            led.red|piros|red|rot|rouge|rojo|czerwony
            led.yellow|sárga|yellow|gelb|jaune|amarillo|żółty
            led.green|zöld|green|grün|vert|verde|zielony
            led.blue|kék|blue|blau|bleu|azul|niebieski
            led.white|fehér|white|weiß|blanc|blanco|biały

            switch.open|nyitott|open|offen|ouvert|abierto|otwarty
            switch.closed|zárt|closed|geschlossen|fermé|cerrado|zamknięty

            n.resistance|Ellenállás|Resistance|Widerstand|Résistance|Resistencia|Rezystancja
            n.power_rating|Terhelhetőség|Power rating|Belastbarkeit|Puissance admissible|Capacidad de potencia|Obciążalność
            n.capacitance|Kapacitás|Capacitance|Kapazität|Capacité|Capacidad|Pojemność
            n.initial_voltage|Kezdeti feszültség|Initial voltage|Anfangsspannung|Tension initiale|Tensión inicial|Napięcie początkowe
            n.voltage_rating|Feszültségtűrés|Voltage rating|Spannungsfestigkeit|Tension admissible|Tensión admisible|Wytrzymałość napięciowa
            n.inductance|Induktivitás|Inductance|Induktivität|Inductance|Inductancia|Indukcyjność
            n.initial_current|Kezdeti áram|Initial current|Anfangsstrom|Courant initial|Corriente inicial|Prąd początkowy
            n.source_voltage|Forrásfeszültség|Source voltage|Quellspannung|Tension de source|Tensión de fuente|Napięcie źródłowe
            n.internal_resistance|Belső ellenállás|Internal resistance|Innenwiderstand|Résistance interne|Resistencia interna|Rezystancja wewnętrzna
            n.waveform|Jelalak|Waveform|Signalform|Forme d'onde|Forma de onda|Kształt sygnału
            n.amplitude|Amplitúdó (csúcs)|Amplitude (peak)|Amplitude (Spitzenwert)|Amplitude (crête)|Amplitud (pico)|Amplituda (szczytowa)
            n.frequency|Frekvencia|Frequency|Frequenz|Fréquence|Frecuencia|Częstotliwość
            n.phase|Fázis|Phase|Phase|Phase|Fase|Faza
            n.dc_offset|Egyenszint (eltolás)|DC offset|Gleichspannungsanteil (Offset)|Décalage continu (offset)|Nivel de continua (offset)|Składowa stała (przesunięcie)
            n.source_current|Forrásáram|Source current|Quellstrom|Courant de source|Corriente de fuente|Prąd źródłowy
            n.sat_current|Záróirányú telítési áram|Reverse saturation current|Sperrsättigungsstrom|Courant de saturation inverse|Corriente de saturación inversa|Prąd nasycenia wstecznego
            n.emission_coeff|Emissziós tényező|Emission coefficient|Emissionskoeffizient|Coefficient d'émission|Coeficiente de emisión|Współczynnik emisji
            n.zener_voltage|Zener-feszültség 5 mA-nél (0 = nincs)|Zener voltage at 5 mA (0 = none)|Zener-Spannung bei 5 mA (0 = keine)|Tension Zener à 5 mA (0 = aucune)|Tensión Zener a 5 mA (0 = ninguna)|Napięcie Zenera przy 5 mA (0 = brak)
            n.max_current|Megengedett áram|Maximum current|Maximal zulässiger Strom|Courant maximal admissible|Corriente máxima admisible|Maksymalny dopuszczalny prąd
            n.color|Szín|Color|Farbe|Couleur|Color|Kolor
            n.forward_voltage|Nyitófeszültség (20 mA-nél)|Forward voltage (at 20 mA)|Durchlassspannung (bei 20 mA)|Tension directe (à 20 mA)|Tensión directa (a 20 mA)|Napięcie przewodzenia (przy 20 mA)
            n.rated_voltage|Névleges feszültség|Rated voltage|Nennspannung|Tension nominale|Tensión nominal|Napięcie znamionowe
            n.rated_power|Névleges teljesítmény|Rated power|Nennleistung|Puissance nominale|Potencia nominal|Moc znamionowa
            n.state|Állapot|State|Zustand|État|Estado|Stan

            m.voltage|Feszültség|Voltage|Spannung|Tension|Tensión|Napięcie
            m.current|Áram|Current|Strom|Courant|Corriente|Prąd
            m.power|Teljesítmény|Power|Leistung|Puissance|Potencia|Moc
            m.charge|Töltés|Charge|Ladung|Charge|Carga|Ładunek
            m.energy|Energia|Energy|Energie|Énergie|Energía|Energia
            m.magenergy|Mágneses energia|Magnetic energy|Magnetische Energie|Énergie magnétique|Energía magnética|Energia magnetyczna
            m.terminalvoltage|Kapocsfeszültség|Terminal voltage|Klemmenspannung|Tension aux bornes|Tensión en bornes|Napięcie na zaciskach
            m.currentoutward|Áram (a + pólusból kifelé)|Current (out of + terminal)|Strom (aus dem +-Pol heraus)|Courant (sortant de la borne +)|Corriente (saliente del polo +)|Prąd (wypływający z bieguna +)
            m.deliveredpower|Leadott teljesítmény|Power delivered|Abgegebene Leistung|Puissance fournie|Potencia entregada|Moc dostarczona
            m.instantvoltage|Pillanatnyi forrásfeszültség|Instantaneous source voltage|Momentane Quellspannung|Tension instantanée de la source|Tensión instantánea de la fuente|Chwilowe napięcie źródła
            m.rms|Effektív érték (szinusz)|RMS value (sine)|Effektivwert (Sinus)|Valeur efficace (sinus)|Valor eficaz (seno)|Wartość skuteczna (sinus)
            m.state_conducting|nyitóirányban vezet|conducting (forward)|leitet (Durchlassrichtung)|conduit (sens direct)|conduce (directo)|przewodzi (w kierunku przewodzenia)
            m.state_zener|Zener-letörésben vezet|conducting (Zener breakdown)|leitet (Zener-Durchbruch)|conduit (claquage Zener)|conduce (ruptura Zener)|przewodzi (przebicie Zenera)
            m.state_blocking|zár|blocking|sperrt|bloque|bloquea|blokuje
            m.brightness|Fényerő|Brightness|Helligkeit|Luminosité|Brillo|Jasność
            m.resistance_calc|Ellenállás|Resistance|Widerstand|Résistance|Resistencia|Rezystancja
            m.refpoint|Referenciapont: 0 V|Reference point: 0 V|Bezugspunkt: 0 V|Point de référence : 0 V|Punto de referencia: 0 V|Punkt odniesienia: 0 V
            m.groundcurrent|A földbe folyó áram|Current into ground|Strom zur Erde|Courant vers la terre|Corriente hacia tierra|Prąd do uziemienia

            warn.resistor|Túlterhelés: %s > %s (melegszik, kiéghet)|Overload: %s > %s (heats up, may burn out)|Überlastung: %s > %s (erhitzt sich, kann durchbrennen)|Surcharge : %s > %s (chauffe, peut griller)|Sobrecarga: %s > %s (se calienta, puede quemarse)|Przeciążenie: %s > %s (nagrzewa się, może się spalić)
            warn.capacitor|Túlfeszültség: %s > %s|Overvoltage: %s > %s|Überspannung: %s > %s|Surtension : %s > %s|Sobretensión: %s > %s|Przepięcie: %s > %s
            warn.diode|Túláram: %s > %s (kiéghet)|Overcurrent: %s > %s (may burn out)|Überstrom: %s > %s (kann durchbrennen)|Surintensité : %s > %s (peut griller)|Sobrecorriente: %s > %s (puede quemarse)|Przetężenie: %s > %s (może się spalić)
            warn.lamp|Túlfeszültség: %s (névleges %s), kiéghet|Overvoltage: %s (rated %s), may burn out|Überspannung: %s (Nennwert %s), kann durchbrennen|Surtension : %s (nominal %s), peut griller|Sobretensión: %s (nominal %s), puede quemarse|Przepięcie: %s (nominalnie %s), może się spalić

            err.loop|Rövidzár vagy feszültségforrás-hurok: %s (%s) kivezetései közvetlenül össze vannak kötve, vagy párhuzamosan kapcsolódik egy másik ideális feszültségforrással / ampermérővel. Adj meg belső ellenállást, vagy javítsd a bekötést.|Short circuit or voltage-source loop: the terminals of %s (%s) are directly connected, or it is wired in parallel with another ideal voltage source / ammeter. Add an internal resistance or fix the wiring.|Kurzschluss oder Spannungsquellen-Schleife: Die Anschlüsse von %s (%s) sind direkt verbunden, oder liegen parallel zu einer anderen idealen Spannungsquelle / einem Amperemeter. Füge einen Innenwiderstand hinzu oder korrigiere die Verdrahtung.|Court-circuit ou boucle de source de tension : les bornes de %s (%s) sont directement connectées, ou il est câblé en parallèle avec une autre source de tension idéale / un ampèremètre. Ajoutez une résistance interne ou corrigez le câblage.|Cortocircuito o bucle de fuente de tensión: los terminales de %s (%s) están conectados directamente, o está conectado en paralelo con otra fuente de tensión ideal / amperímetro. Añade una resistencia interna o corrige el cableado.|Zwarcie lub pętla źródła napięcia: zaciski %s (%s) są połączone bezpośrednio, albo element jest połączony równolegle z innym idealnym źródłem napięcia / amperomierzem. Dodaj rezystancję wewnętrzną lub popraw połączenia.
            err.singular|Az áramkör egyenletrendszere nem oldható meg (%s). Ellenőrizd a bekötést (pl. rövidre zárt forrás, sorba kötött áramforrások).|The circuit's system of equations cannot be solved (%s). Check the wiring (e.g. a shorted source, current sources in series).|Das Gleichungssystem der Schaltung kann nicht gelöst werden (%s). Überprüfe die Verdrahtung (z. B. kurzgeschlossene Quelle, in Reihe geschaltete Stromquellen).|Le système d'équations du circuit ne peut pas être résolu (%s). Vérifiez le câblage (p. ex. source en court-circuit, sources de courant en série).|El sistema de ecuaciones del circuito no se puede resolver (%s). Comprueba el cableado (p. ej. fuente en cortocircuito, fuentes de corriente en serie).|Układu równań obwodu nie można rozwiązać (%s). Sprawdź połączenia (np. zwarte źródło, szeregowo połączone źródła prądowe).
            err.label|Hiba: %s|Error: %s|Fehler: %s|Erreur : %s|Error: %s|Błąd: %s
            err.singular_matrix|szinguláris mátrix|singular matrix|singuläre Matrix|matrice singulière|matriz singular|macierz singularna
            err.numeric|numerikus hiba|numerical error|numerischer Fehler|erreur numérique|error numérico|błąd numeryczny

            menu.file|Fájl|File|Datei|Fichier|Archivo|Plik
            menu.file.new|Új áramkör|New circuit|Neue Schaltung|Nouveau circuit|Nuevo circuito|Nowy obwód
            menu.file.open|Megnyitás…|Open…|Öffnen…|Ouvrir…|Abrir…|Otwórz…
            menu.file.save|Mentés|Save|Speichern|Enregistrer|Guardar|Zapisz
            menu.file.saveas|Mentés másként…|Save As…|Speichern als…|Enregistrer sous…|Guardar como…|Zapisz jako…
            menu.file.examples|Példák|Examples|Beispiele|Exemples|Ejemplos|Przykłady
            menu.file.exit|Kilépés|Exit|Beenden|Quitter|Salir|Zamknij
            menu.edit|Szerkesztés|Edit|Bearbeiten|Édition|Edición|Edycja
            menu.edit.undo|Visszavonás|Undo|Rückgängig|Annuler|Deshacer|Wstecz
            menu.edit.redo|Újra|Redo|Wiederholen|Rétablir|Rehacer|Powtórz
            menu.edit.delete|Kijelölt törlése  (Del)|Delete selected  (Del)|Auswahl löschen  (Entf)|Supprimer la sélection  (Suppr)|Eliminar selección  (Supr)|Usuń wybrane  (Del)
            menu.edit.rotate|Kijelölt forgatása 90°-kal  (R)|Rotate selected 90°  (R)|Auswahl um 90° drehen  (R)|Rotation de la sélection de 90°  (R)|Girar selección 90°  (R)|Obróć wybrane o 90°  (R)
            menu.edit.duplicate|Kijelölt kettőzése|Duplicate selected|Auswahl duplizieren|Dupliquer la sélection|Duplicar selección|Duplikuj wybrane
            menu.sim|Szimuláció|Simulation|Simulation|Simulation|Simulación|Symulacja
            menu.sim.startstop|Indítás / leállítás  (Szóköz)|Start / stop  (Space)|Start / Stopp  (Leertaste)|Démarrer / arrêter  (Espace)|Iniciar / detener  (Espacio)|Start / stop  (Spacja)
            menu.sim.step|Egy lépés  (.)|Single step  (.)|Einzelschritt  (.)|Un pas  (.)|Un paso  (.)|Jeden krok  (.)
            menu.sim.reset|Alaphelyzet|Reset|Zurücksetzen|Réinitialisation|Reiniciar|Reset
            menu.sim.settings|Szimulációs beállítások…|Simulation settings…|Simulationseinstellungen…|Paramètres de simulation…|Ajustes de simulación…|Ustawienia symulacji…
            menu.view|Nézet|View|Ansicht|Affichage|Vista|Widok
            menu.view.colors|Feszültségek színezése|Color voltages|Spannungen einfärben|Colorer les tensions|Colorear tensiones|Koloruj napięcia
            menu.view.animation|Áramlás animációja|Current flow animation|Stromfluss-Animation|Animation du flux de courant|Animación del flujo de corriente|Animacja przepływu prądu
            menu.view.currents|Áramerősségek kiírása|Show current values|Stromwerte anzeigen|Afficher les valeurs de courant|Mostrar valores de corriente|Pokaż wartości prądu
            menu.view.zoomin|Nagyítás|Zoom In|Vergrößern|Zoom avant|Acercar|Powiększ
            menu.view.zoomout|Kicsinyítés|Zoom Out|Verkleinern|Zoom arrière|Alejar|Zmniejsz
            menu.view.fit|Áramkör illesztése az ablakba|Fit circuit to window|Schaltung an Fenster anpassen|Ajuster le circuit à la fenêtre|Ajustar circuito a la ventana|Dopasuj obwód do okna
            menu.lang|Nyelv|Language|Sprache|Langue|Idioma|Język
            menu.help|Súgó|Help|Hilfe|Aide|Ayuda|Pomoc
            menu.help.usage|Használat|Usage|Bedienung|Utilisation|Uso|Użycie
            menu.help.about|Névjegy|About|Info|À propos|Acerca de|O programie
            dlg.about|CIRCUIT SIMULATOR\\n\\ncreated by Sisak László (helmetsoft.com)\\n\\nfree software, freely distributable|CIRCUIT SIMULATOR\\n\\ncreated by Sisak László (helmetsoft.com)\\n\\nfree software, freely distributable|CIRCUIT SIMULATOR\\n\\ncreated by Sisak László (helmetsoft.com)\\n\\nfree software, freely distributable|CIRCUIT SIMULATOR\\n\\ncreated by Sisak László (helmetsoft.com)\\n\\nfree software, freely distributable|CIRCUIT SIMULATOR\\n\\ncreated by Sisak László (helmetsoft.com)\\n\\nfree software, freely distributable|CIRCUIT SIMULATOR\\n\\ncreated by Sisak László (helmetsoft.com)\\n\\nfree software, freely distributable

            dlg.unsaved|Az áramkör módosult. Mented?|The circuit has been modified. Save it?|Die Schaltung wurde geändert. Speichern?|Le circuit a été modifié. L'enregistrer ?|El circuito se ha modificado. ¿Guardarlo?|Obwód został zmodyfikowany. Zapisać?
            dlg.open.fail|Nem sikerült megnyitni:\\n%s|Could not open:\\n%s|Öffnen fehlgeschlagen:\\n%s|Échec de l'ouverture :\\n%s|No se pudo abrir:\\n%s|Nie udało się otworzyć:\\n%s
            dlg.save.fail|Nem sikerült menteni:\\n%s|Could not save:\\n%s|Speichern fehlgeschlagen:\\n%s|Échec de l'enregistrement :\\n%s|No se pudo guardar:\\n%s|Nie udało się zapisać:\\n%s
            filefilter|Áramkör (*.aramkor)|Circuit (*.aramkor)|Schaltung (*.aramkor)|Circuit (*.aramkor)|Circuito (*.aramkor)|Obwód (*.aramkor)
            dlg.settings.title|Szimulációs beállítások|Simulation settings|Simulationseinstellungen|Paramètres de simulation|Ajustes de simulación|Ustawienia symulacji
            dlg.settings.dt|Időlépés (pl. 10u, 1m):|Time step (e.g. 10u, 1m):|Zeitschritt (z. B. 10u, 1m):|Pas de temps (p. ex. 10u, 1m) :|Paso de tiempo (p. ej. 10u, 1m):|Krok czasowy (np. 10u, 1m):
            dlg.settings.method|Integrálási módszer:|Integration method:|Integrationsmethode:|Méthode d'intégration :|Método de integración:|Metoda integracji:
            method.trapez|Trapéz-módszer (pontosabb)|Trapezoidal method (more accurate)|Trapezmethode (genauer)|Méthode trapézoïdale (plus précise)|Método trapezoidal (más preciso)|Metoda trapezów (dokładniejsza)
            method.euler|Implicit Euler (stabilabb kapcsoláskor)|Implicit Euler (more stable with switching)|Implizites Euler-Verfahren (stabiler bei Schaltvorgängen)|Euler implicite (plus stable lors des commutations)|Euler implícito (más estable con conmutaciones)|Niejawna metoda Eulera (stabilniejsza przy przełączaniu)
            dlg.settings.dt.invalid|Érvénytelen időlépés (1 ps … 1 s).|Invalid time step (1 ps … 1 s).|Ungültiger Zeitschritt (1 ps … 1 s).|Pas de temps invalide (1 ps … 1 s).|Paso de tiempo no válido (1 ps … 1 s).|Nieprawidłowy krok czasowy (1 ps … 1 s).

            panel.measured|Mért értékek|Measured values|Messwerte|Valeurs mesurées|Valores medidos|Wartości zmierzone
            panel.params|Paraméterek|Parameters|Parameter|Paramètres|Parámetros|Parametry
            panel.none|Nincs kijelölt alkatrész|No component selected|Kein Bauteil ausgewählt|Aucun composant sélectionné|Ningún componente seleccionado|Brak wybranego elementu
            panel.hint|<html>Kattints egy alkatrészre a paraméterei<br>és mért értékei megjelenítéséhez.</html>|<html>Click a component to see its<br>parameters and measured values.</html>|<html>Klicke auf ein Bauteil, um seine<br>Parameter und Messwerte anzuzeigen.</html>|<html>Cliquez sur un composant pour afficher<br>ses paramètres et ses valeurs mesurées.</html>|<html>Haz clic en un componente para ver<br>sus parámetros y valores medidos.</html>|<html>Kliknij element, aby zobaczyć jego<br>parametry i zmierzone wartości.</html>
            panel.noparams|Ennek az alkatrésznek nincs paramétere.|This component has no parameters.|Dieses Bauteil hat keine Parameter.|Ce composant n'a aucun paramètre.|Este componente no tiene parámetros.|Ten element nie ma parametrów.
            panel.switchtip|<html><i>Tipp: kattints a kapcsolóra a rajzon a váltáshoz.</i></html>|<html><i>Tip: click the switch in the drawing to toggle it.</i></html>|<html><i>Tipp: Klicke im Schaltplan auf den Schalter, um ihn zu betätigen.</i></html>|<html><i>Astuce : cliquez sur l'interrupteur dans le schéma pour basculer.</i></html>|<html><i>Consejo: haz clic en el interruptor del esquema para cambiarlo.</i></html>|<html><i>Wskazówka: kliknij przełącznik na schemacie, aby go przełączyć.</i></html>
            panel.startsim|Indítsd el a szimulációt (▶ vagy Szóköz)\\na mért értékek megjelenítéséhez.|Start the simulation (▶ or Space)\\nto see the measured values.|Starte die Simulation (▶ oder Leertaste),\\num die Messwerte anzuzeigen.|Démarrez la simulation (▶ ou Espace)\\npour afficher les valeurs mesurées.|Inicia la simulación (▶ o Espacio)\\npara ver los valores medidos.|Uruchom symulację (▶ lub Spacja),\\naby zobaczyć zmierzone wartości.
            panel.partcount|Alkatrészek: %d|Components: %d|Bauteile: %d|Composants : %d|Componentes: %d|Elementy: %d
            panel.nodecount|Csomópontok: %d|Nodes: %d|Knoten: %d|Nœuds : %d|Nodos: %d|Węzły: %d
            panel.simtime|Szimulált idő:|Simulated time:|Simulierte Zeit:|Temps simulé :|Tiempo simulado:|Symulowany czas:
            panel.totalpower|Források összes teljesítménye:|Total power from sources:|Gesamtleistung der Quellen:|Puissance totale des sources :|Potencia total de las fuentes:|Całkowita moc źródeł:
            panel.overloaded|%d alkatrész túlterhelt – kattints rájuk a részletekért.|%d component(s) overloaded – click them for details.|%d Bauteil(e) überlastet – klicke darauf für Details.|%d composant(s) en surcharge – cliquez pour les détails.|%d componente(s) sobrecargado(s) – haz clic para ver detalles.|%d element(y) przeciążony(ch) – kliknij, aby zobaczyć szczegóły.

            field.invalid|Érvénytelen érték. Példák: 4.7k, 10u, 100n, 2,2M (tartomány: %s … %s)|Invalid value. Examples: 4.7k, 10u, 100n, 2.2M (range: %s … %s)|Ungültiger Wert. Beispiele: 4.7k, 10u, 100n, 2,2M (Bereich: %s … %s)|Valeur invalide. Exemples : 4.7k, 10u, 100n, 2,2M (plage : %s … %s)|Valor no válido. Ejemplos: 4.7k, 10u, 100n, 2,2M (rango: %s … %s)|Nieprawidłowa wartość. Przykłady: 4.7k, 10u, 100n, 2,2M (zakres: %s … %s)
            field.sitip|SI-prefixum használható: p, n, u (µ), m, k, M, G – pl. 4.7k vagy 100n|SI prefixes can be used: p, n, u (µ), m, k, M, G – e.g. 4.7k or 100n|SI-Präfixe sind möglich: p, n, u (µ), m, k, M, G – z. B. 4.7k oder 100n|Préfixes SI possibles : p, n, u (µ), m, k, M, G – p. ex. 4.7k ou 100n|Se pueden usar prefijos SI: p, n, u (µ), m, k, M, G – p. ej. 4.7k o 100n|Można używać przedrostków SI: p, n, u (µ), m, k, M, G – np. 4.7k lub 100n

            tool.select|Kijelölés|Select|Auswahl|Sélection|Selección|Wybór
            tool.select.tip|Kijelölés, mozgatás (Esc)|Select, move (Esc)|Auswählen, verschieben (Esc)|Sélectionner, déplacer (Échap)|Seleccionar, mover (Esc)|Wybieranie, przesuwanie (Esc)
            tool.tip|%s – húzd az egeret a rácson a két kivezetés között|%s – drag the mouse on the grid between the two terminals|%s – ziehe die Maus im Raster zwischen den beiden Anschlüssen|%s – faites glisser la souris sur la grille entre les deux bornes|%s – arrastra el ratón en la cuadrícula entre los dos terminales|%s – przeciągnij mysz po siatce między dwoma zaciskami
            btn.start|▶ Indítás|▶ Start|▶ Start|▶ Démarrer|▶ Iniciar|▶ Start
            btn.stop|⏸ Leállítás|⏸ Stop|⏸ Stopp|⏸ Arrêter|⏸ Detener|⏸ Zatrzymaj
            btn.reset|⟲ Alaphelyzet|⟲ Reset|⟲ Zurücksetzen|⟲ Réinitialiser|⟲ Reiniciar|⟲ Resetuj
            btn.reset.tip|Idő nullázása, kondenzátorok és tekercsek kezdeti állapota|Reset time, capacitors and inductors to their initial state|Zeit zurücksetzen, Anfangszustand von Kondensatoren und Spulen|Réinitialiser le temps, condensateurs et bobines à leur état initial|Reiniciar el tiempo, condensadores y bobinas a su estado inicial|Resetuj czas, kondensatory i cewki do stanu początkowego
            lbl.speed|Sebesség|Speed|Geschwindigkeit|Vitesse|Velocidad|Prędkość
            slider.speed.tip|Mennyi szimulált idő teljen el egy valós másodperc alatt|How much simulated time should pass per real second|Wie viel simulierte Zeit pro realer Sekunde vergehen soll|Combien de temps simulé doit s'écouler par seconde réelle|Cuánto tiempo simulado debe transcurrir por segundo real|Ile symulowanego czasu ma mijać w ciągu jednej rzeczywistej sekundy

            osc.title|Oszcilloszkóp – a kijelölt alkatrész feszültsége (zöld) és árama (narancs).   Időablak:|Oscilloscope – voltage (green) and current (orange) of the selected component.   Time window:|Oszilloskop – Spannung (grün) und Strom (orange) des ausgewählten Bauteils.   Zeitfenster:|Oscilloscope – tension (vert) et courant (orange) du composant sélectionné.   Fenêtre temporelle :|Osciloscopio – tensión (verde) y corriente (naranja) del componente seleccionado.   Ventana de tiempo:|Oscyloskop – napięcie (zielony) i prąd (pomarańczowy) wybranego elementu.   Okno czasowe:
            osc.waitselect|Jelölj ki egy alkatrészt, és indítsd el a szimulációt.|Select a component and start the simulation.|Wähle ein Bauteil aus und starte die Simulation.|Sélectionnez un composant et démarrez la simulation.|Selecciona un componente e inicia la simulación.|Wybierz element i uruchom symulację.
            osc.collecting|Adatgyűjtés…|Collecting data…|Datenerfassung…|Collecte des données…|Recopilando datos…|Zbieranie danych…
            osc.perdiv|/osztás|/div|/Div|/div|/div|/dz.
            osc.time|t = %s|t = %s|t = %s|t = %s|t = %s|t = %s

            canvas.hint1|Válassz alkatrészt az eszköztárból, majd húzd az egeret a rácson két pont között.|Choose a component from the toolbar, then drag the mouse between two points on the grid.|Wähle ein Bauteil aus der Werkzeugleiste und ziehe die Maus zwischen zwei Rasterpunkten.|Choisissez un composant dans la barre d'outils, puis faites glisser la souris entre deux points de la grille.|Elige un componente de la barra de herramientas y arrastra el ratón entre dos puntos de la cuadrícula.|Wybierz element z paska narzędzi, a następnie przeciągnij mysz między dwoma punktami siatki.
            canvas.hint2|Kész példákat a Fájl → Példák menüben találsz.|Ready-made examples are in the File → Examples menu.|Fertige Beispiele findest du im Menü Datei → Beispiele.|Des exemples prêts à l'emploi se trouvent dans le menu Fichier → Exemples.|Encontrarás ejemplos listos en el menú Archivo → Ejemplos.|Gotowe przykłady znajdziesz w menu Plik → Przykłady.

            status.running|● Fut|● Running|● Läuft|● En cours|● Ejecutando|● Działa
            status.stopped|■ Áll|■ Stopped|■ Angehalten|■ Arrêté|■ Detenido|■ Zatrzymany
            status.time|Idő:|Time:|Zeit:|Temps :|Tiempo:|Czas:
            status.timestep|Időlépés:|Time step:|Zeitschritt:|Pas de temps :|Paso de tiempo:|Krok czasowy:
            status.stepspersec|lépés/s|steps/s|Schritte/s|pas/s|pasos/s|kroków/s
            status.slow|(a gép nem bírja a kért sebességet)|(the computer can't keep up with the requested speed)|(der Computer kommt mit der gewünschten Geschwindigkeit nicht mit)|(l'ordinateur n'arrive pas à suivre la vitesse demandée)|(el ordenador no puede seguir la velocidad solicitada)|(komputer nie nadąża z żądaną prędkością)
            status.convergence|konvergenciahiba:|convergence errors:|Konvergenzfehler:|erreurs de convergence :|errores de convergencia:|błędy zbieżności:
            status.grid|Rács:|Grid:|Raster:|Grille :|Cuadrícula:|Siatka:
            status.place|Elhelyezés: húzd az egeret a két kivezetés között|Placing: drag the mouse between the two terminals|Platzieren: ziehe die Maus zwischen die beiden Anschlüsse|Placement : faites glisser la souris entre les deux bornes|Colocación: arrastra el ratón entre los dos terminales|Umieszczanie: przeciągnij mysz między dwoma zaciskami

            ex.led1.name|LED előtét-ellenállással|LED with series resistor|LED mit Vorwiderstand|LED avec résistance série|LED con resistencia en serie|LED z rezystorem szeregowym
            ex.led1.desc|LED: I ≈ (9 V − 1,9 V) / 470 Ω ≈ 15 mA. Próbáld ki kisebb ellenállással is!|LED: I ≈ (9 V − 1.9 V) / 470 Ω ≈ 15 mA. Try it with a smaller resistor too!|LED: I ≈ (9 V − 1,9 V) / 470 Ω ≈ 15 mA. Probiere es auch mit einem kleineren Widerstand!|LED : I ≈ (9 V − 1,9 V) / 470 Ω ≈ 15 mA. Essayez aussi avec une résistance plus petite !|LED: I ≈ (9 V − 1,9 V) / 470 Ω ≈ 15 mA. ¡Pruébalo también con una resistencia más pequeña!|LED: I ≈ (9 V − 1,9 V) / 470 Ω ≈ 15 mA. Spróbuj też z mniejszym rezystorem!
            ex.divider.name|Feszültségosztó|Voltage divider|Spannungsteiler|Diviseur de tension|Divisor de tensión|Dzielnik napięcia
            ex.divider.desc|Feszültségosztó: U2 = 12 V · 2 kΩ / 3 kΩ = 8 V, I = 4 mA.|Voltage divider: U2 = 12 V · 2 kΩ / 3 kΩ = 8 V, I = 4 mA.|Spannungsteiler: U2 = 12 V · 2 kΩ / 3 kΩ = 8 V, I = 4 mA.|Diviseur de tension : U2 = 12 V · 2 kΩ / 3 kΩ = 8 V, I = 4 mA.|Divisor de tensión: U2 = 12 V · 2 kΩ / 3 kΩ = 8 V, I = 4 mA.|Dzielnik napięcia: U2 = 12 V · 2 kΩ / 3 kΩ = 8 V, I = 4 mA.
            ex.rc.name|RC kör töltése (τ = 1 s)|RC circuit charging (τ = 1 s)|RC-Schaltung beim Laden (τ = 1 s)|Charge d'un circuit RC (τ = 1 s)|Carga de un circuito RC (τ = 1 s)|Ładowanie obwodu RC (τ = 1 s)
            ex.rc.desc|RC töltés: τ = R·C = 1 s, 1 s után UC ≈ 6,32 V. Kattints a kapcsolóra a szakításhoz.|RC charging: τ = R·C = 1 s, after 1 s UC ≈ 6.32 V. Click the switch to open the circuit.|RC-Ladung: τ = R·C = 1 s, nach 1 s ist UC ≈ 6,32 V. Klicke auf den Schalter zum Unterbrechen.|Charge RC : τ = R·C = 1 s, après 1 s UC ≈ 6,32 V. Cliquez sur l'interrupteur pour ouvrir le circuit.|Carga RC: τ = R·C = 1 s, tras 1 s UC ≈ 6,32 V. Haz clic en el interruptor para abrir el circuito.|Ładowanie RC: τ = R·C = 1 s, po 1 s UC ≈ 6,32 V. Kliknij przełącznik, aby przerwać obwód.
            ex.rlc.name|Soros RLC rezgőkör (rezonancia 503 Hz)|Series RLC resonant circuit (resonance at 503 Hz)|Serien-RLC-Schwingkreis (Resonanz bei 503 Hz)|Circuit résonant RLC série (résonance à 503 Hz)|Circuito resonante RLC en serie (resonancia a 503 Hz)|Szeregowy obwód rezonansowy RLC (rezonans 503 Hz)
            ex.rlc.desc|Rezonancia: f0 = 1/(2π√LC) ≈ 503 Hz, áram ≈ 0,5 A, a kondenzátoron ≈ 16 V csúcsfeszültség (Q ≈ 3,2).|Resonance: f0 = 1/(2π√LC) ≈ 503 Hz, current ≈ 0.5 A, peak voltage across the capacitor ≈ 16 V (Q ≈ 3.2).|Resonanz: f0 = 1/(2π√LC) ≈ 503 Hz, Strom ≈ 0,5 A, Spitzenspannung am Kondensator ≈ 16 V (Q ≈ 3,2).|Résonance : f0 = 1/(2π√LC) ≈ 503 Hz, courant ≈ 0,5 A, tension crête sur le condensateur ≈ 16 V (Q ≈ 3,2).|Resonancia: f0 = 1/(2π√LC) ≈ 503 Hz, corriente ≈ 0,5 A, tensión de pico en el condensador ≈ 16 V (Q ≈ 3,2).|Rezonans: f0 = 1/(2π√LC) ≈ 503 Hz, prąd ≈ 0,5 A, napięcie szczytowe na kondensatorze ≈ 16 V (Q ≈ 3,2).
            ex.bridge.name|Graetz-híd pufferkondenzátorral|Full-wave bridge rectifier with filter capacitor|Graetz-Brücke mit Siebkondensator|Pont de Graetz avec condensateur de filtrage|Puente de Graetz con condensador de filtro|Mostek Graetza z kondensatorem filtrującym
            ex.bridge.desc|Kétutas egyenirányító: ≈ 12 V − 2 · 0,7 V ≈ 10,5 V egyenfeszültség kis hullámossággal.|Full-wave rectifier: ≈ 12 V − 2 · 0.7 V ≈ 10.5 V DC with small ripple.|Zweiweggleichrichter: ≈ 12 V − 2 · 0,7 V ≈ 10,5 V Gleichspannung mit kleiner Welligkeit.|Redresseur double alternance : ≈ 12 V − 2 · 0,7 V ≈ 10,5 V continu avec faible ondulation.|Rectificador de onda completa: ≈ 12 V − 2 · 0,7 V ≈ 10,5 V de continua con poco rizado.|Prostownik dwupołówkowy: ≈ 12 V − 2 · 0,7 V ≈ 10,5 V napięcia stałego z niewielkim tętnieniem.
            ex.zener.name|Zener-stabilizátor|Zener voltage regulator|Zener-Spannungsstabilisator|Régulateur de tension Zener|Regulador de tensión Zener|Stabilizator napięcia Zenera
            ex.zener.desc|Zener-stabilizátor: a kimenet ≈ 5,1 V marad. Változtasd a telep feszültségét!|Zener regulator: the output stays ≈ 5.1 V. Try changing the battery voltage!|Zener-Stabilisator: der Ausgang bleibt bei ≈ 5,1 V. Ändere die Batteriespannung!|Régulateur Zener : la sortie reste à ≈ 5,1 V. Essayez de changer la tension de la pile !|Regulador Zener: la salida se mantiene en ≈ 5,1 V. ¡Prueba a cambiar la tensión de la pila!|Stabilizator Zenera: wyjście pozostaje na poziomie ≈ 5,1 V. Spróbuj zmienić napięcie baterii!
            ex.rl.name|RL kör bekapcsolása|RL circuit switch-on|RL-Schaltung beim Einschalten|Mise sous tension d'un circuit RL|Conexión de un circuito RL|Włączanie obwodu RL
            ex.rl.desc|RL kör: τ = L/R = 10 ms. Kattints a kapcsolóra! Kikapcsoláskor a tekercs árama az izzón át folyik tovább (felvillan).|RL circuit: τ = L/R = 10 ms. Click the switch! When switched off, the inductor's current keeps flowing through the lamp (it flashes).|RL-Schaltung: τ = L/R = 10 ms. Klicke auf den Schalter! Beim Ausschalten fließt der Spulenstrom weiter durch die Lampe (sie blitzt auf).|Circuit RL : τ = L/R = 10 ms. Cliquez sur l'interrupteur ! À la coupure, le courant de la bobine continue de circuler dans l'ampoule (elle s'allume brièvement).|Circuito RL: τ = L/R = 10 ms. ¡Haz clic en el interruptor! Al desconectar, la corriente de la bobina sigue fluyendo por la bombilla (destella).|Obwód RL: τ = L/R = 10 ms. Kliknij przełącznik! Po wyłączeniu prąd cewki płynie dalej przez żarówkę (błyska).
            """;

    private static final Map<String, String[]> T = new HashMap<>();

    static {
        for (String sor : TABLA.split("\n")) {
            sor = sor.strip();
            if (sor.isEmpty() || sor.startsWith("#")) continue;
            String[] r = sor.split("\\|");
            if (r.length != 7) throw new IllegalStateException("Hibás fordítási sor (" + r.length + " mező): " + sor);
            for (int i = 1; i < r.length; i++) r[i] = r[i].replace("\\n", "\n");
            T.put(r[0], Arrays.copyOfRange(r, 1, 7));
        }
    }

    // ================================================================ súgó-szövegek (nyelvenként)

    private static final String SUGO_HU = String.join("\n",
            "ÁRAMKÖR ÉPÍTÉSE",
            "  • Válassz alkatrészt az eszköztárból, majd nyomd le az egeret a rácson és húzd a másik kivezetés helyére.",
            "  • Az alkatrészek a végpontjaikban kapcsolódnak egymáshoz (a fekete pötty 3+ kivezetés találkozását jelzi,",
            "    a piros karika be nem kötött végpontot).",
            "  • Kijelölés módban (Esc) kattintással kijelölsz, húzással mozgatsz; a kék négyzetekkel a végpontot húzhatod.",
            "  • Del: törlés, R: forgatás 90°-kal, Ctrl+D: kettőzés, Ctrl+Z / Ctrl+Y: visszavonás / újra.",
            "  • Jobb vagy középső egérgombbal húzva a nézet mozgatható, görgővel nagyítható.",
            "",
            "PARAMÉTEREK",
            "  • A kijelölt alkatrész paraméterei a jobb oldali panelen szerkeszthetők (Enter-rel érvényesül).",
            "  • SI-prefixumok használhatók: p, n, u (µ), m, k, M, G – pl. 4.7k, 100n, 2,2M.",
            "  • Polaritás: telepnél és forrásnál a B (második) végpont a pozitív; diódánál A az anód, B a katód.",
            "",
            "SZIMULÁCIÓ",
            "  • Szóköz vagy ▶: indítás / leállítás. A vezetékek színe a feszültséget mutatja (zöld +, piros −),",
            "    a sárga pöttyök az áram irányát és nagyságát (konvencionális áramirány).",
            "  • A kapcsolóra kattintva szimuláció közben is kapcsolhatsz.",
            "  • Az oszcilloszkóp a kijelölt alkatrész feszültségét és áramát rajzolja.",
            "  • A sebesség csúszkával állítható, hogy egy valós másodperc alatt mennyi szimulált idő teljen el.",
            "  • A túlterhelt alkatrészeket ⚠ jelzi (ellenállás teljesítménye, LED/dióda árama, kondenzátor feszültsége).",
            "",
            "MODELLEK",
            "  • Módosított csomóponti analízis (MNA); kondenzátor és tekercs trapéz- vagy implicit Euler-módszerrel;",
            "    dióda / LED Shockley-egyenlettel Newton–Raphson-iterációval, Zener-letöréssel.");

    private static final String SUGO_EN = String.join("\n",
            "BUILDING A CIRCUIT",
            "  • Choose a component from the toolbar, then press the mouse button on the grid and drag to the other terminal.",
            "  • Components connect to each other at their terminals (a black dot marks where 3+ terminals meet,",
            "    a red circle marks an unconnected terminal).",
            "  • In select mode (Esc), click to select, drag to move; the blue squares let you drag a terminal.",
            "  • Del: delete, R: rotate 90°, Ctrl+D: duplicate, Ctrl+Z / Ctrl+Y: undo / redo.",
            "  • Drag with the right or middle mouse button to pan the view, use the scroll wheel to zoom.",
            "",
            "PARAMETERS",
            "  • The selected component's parameters can be edited in the panel on the right (confirmed with Enter).",
            "  • SI prefixes can be used: p, n, u (µ), m, k, M, G – e.g. 4.7k, 100n, 2.2M.",
            "  • Polarity: for batteries and sources, terminal B (the second one) is positive; for diodes, A is the anode, B the cathode.",
            "",
            "SIMULATION",
            "  • Space or ▶: start / stop. The color of the wires shows the voltage (green +, red −),",
            "    the yellow dots show the direction and magnitude of the current (conventional current direction).",
            "  • You can click a switch to toggle it even while the simulation is running.",
            "  • The oscilloscope plots the voltage and current of the selected component.",
            "  • The speed slider sets how much simulated time passes per real second.",
            "  • Overloaded components are marked with ⚠ (resistor power, LED/diode current, capacitor voltage).",
            "",
            "MODELS",
            "  • Modified nodal analysis (MNA); capacitors and inductors use the trapezoidal or implicit Euler method;",
            "    diodes / LEDs use the Shockley equation with Newton–Raphson iteration and Zener breakdown.");

    private static final String SUGO_DE = String.join("\n",
            "SCHALTUNG AUFBAUEN",
            "  • Wähle ein Bauteil aus der Werkzeugleiste, drücke dann die Maustaste auf dem Raster und ziehe zum anderen Anschluss.",
            "  • Bauteile verbinden sich an ihren Anschlüssen (ein schwarzer Punkt zeigt das Zusammentreffen von 3+ Anschlüssen,",
            "    ein roter Kreis einen unverbundenen Anschluss).",
            "  • Im Auswahlmodus (Esc) wählst du mit Klick aus, mit Ziehen verschiebst du; mit den blauen Quadraten ziehst du einen Anschluss.",
            "  • Entf: löschen, R: um 90° drehen, Strg+D: duplizieren, Strg+Z / Strg+Y: rückgängig / wiederholen.",
            "  • Mit der rechten oder mittleren Maustaste ziehen verschiebt die Ansicht, mit dem Mausrad zoomst du.",
            "",
            "PARAMETER",
            "  • Die Parameter des ausgewählten Bauteils lassen sich im rechten Panel bearbeiten (mit Enter übernehmen).",
            "  • SI-Präfixe sind möglich: p, n, u (µ), m, k, M, G – z. B. 4.7k, 100n, 2,2M.",
            "  • Polarität: bei Batterie und Quelle ist Anschluss B (der zweite) positiv; bei der Diode ist A die Anode, B die Kathode.",
            "",
            "SIMULATION",
            "  • Leertaste oder ▶: Start / Stopp. Die Farbe der Drähte zeigt die Spannung (grün +, rot −),",
            "    die gelben Punkte zeigen Richtung und Stärke des Stroms (konventionelle Stromrichtung).",
            "  • Du kannst auch während der Simulation auf einen Schalter klicken, um ihn zu betätigen.",
            "  • Das Oszilloskop zeichnet Spannung und Strom des ausgewählten Bauteils.",
            "  • Mit dem Geschwindigkeitsregler stellst du ein, wie viel simulierte Zeit pro realer Sekunde vergeht.",
            "  • Überlastete Bauteile werden mit ⚠ markiert (Widerstandsleistung, LED-/Diodenstrom, Kondensatorspannung).",
            "",
            "MODELLE",
            "  • Modifizierte Knotenpunktanalyse (MNA); Kondensator und Spule mit Trapez- oder implizitem Euler-Verfahren;",
            "    Diode / LED mit der Shockley-Gleichung per Newton-Raphson-Iteration, mit Zener-Durchbruch.");

    private static final String SUGO_FR = String.join("\n",
            "CONSTRUCTION DU CIRCUIT",
            "  • Choisissez un composant dans la barre d'outils, puis appuyez sur le bouton de la souris sur la grille et faites glisser jusqu'à l'autre borne.",
            "  • Les composants se connectent par leurs bornes (un point noir indique la rencontre de 3 bornes ou plus,",
            "    un cercle rouge une borne non connectée).",
            "  • En mode sélection (Échap), cliquez pour sélectionner, faites glisser pour déplacer ; les carrés bleus permettent de déplacer une borne.",
            "  • Suppr : supprimer, R : rotation de 90°, Ctrl+D : dupliquer, Ctrl+Z / Ctrl+Y : annuler / rétablir.",
            "  • Faites glisser avec le bouton droit ou du milieu pour déplacer la vue, utilisez la molette pour zoomer.",
            "",
            "PARAMÈTRES",
            "  • Les paramètres du composant sélectionné sont modifiables dans le panneau de droite (valider avec Entrée).",
            "  • Les préfixes SI sont possibles : p, n, u (µ), m, k, M, G – p. ex. 4.7k, 100n, 2,2M.",
            "  • Polarité : pour la pile et la source, la borne B (la seconde) est positive ; pour la diode, A est l'anode, B la cathode.",
            "",
            "SIMULATION",
            "  • Espace ou ▶ : démarrer / arrêter. La couleur des fils indique la tension (vert +, rouge −),",
            "    les points jaunes indiquent la direction et l'intensité du courant (sens conventionnel du courant).",
            "  • Vous pouvez cliquer sur un interrupteur pour le basculer même pendant la simulation.",
            "  • L'oscilloscope trace la tension et le courant du composant sélectionné.",
            "  • Le curseur de vitesse règle la quantité de temps simulé qui s'écoule par seconde réelle.",
            "  • Les composants en surcharge sont signalés par ⚠ (puissance de la résistance, courant de la LED/diode, tension du condensateur).",
            "",
            "MODÈLES",
            "  • Analyse nodale modifiée (MNA) ; condensateur et bobine avec méthode trapézoïdale ou Euler implicite ;",
            "    diode / LED avec l'équation de Shockley par itération de Newton-Raphson, avec claquage Zener.");

    private static final String SUGO_ES = String.join("\n",
            "CONSTRUCCIÓN DEL CIRCUITO",
            "  • Elige un componente de la barra de herramientas, pulsa el botón del ratón en la cuadrícula y arrastra hasta el otro terminal.",
            "  • Los componentes se conectan por sus terminales (un punto negro indica la unión de 3 o más terminales,",
            "    un círculo rojo un terminal sin conectar).",
            "  • En modo selección (Esc), haz clic para seleccionar, arrastra para mover; los cuadrados azules permiten arrastrar un terminal.",
            "  • Supr: eliminar, R: girar 90°, Ctrl+D: duplicar, Ctrl+Z / Ctrl+Y: deshacer / rehacer.",
            "  • Arrastra con el botón derecho o central del ratón para desplazar la vista, usa la rueda para acercar o alejar.",
            "",
            "PARÁMETROS",
            "  • Los parámetros del componente seleccionado se editan en el panel derecho (se confirman con Intro).",
            "  • Se pueden usar prefijos SI: p, n, u (µ), m, k, M, G – p. ej. 4.7k, 100n, 2,2M.",
            "  • Polaridad: en la pila y la fuente, el terminal B (el segundo) es el positivo; en el diodo, A es el ánodo y B el cátodo.",
            "",
            "SIMULACIÓN",
            "  • Espacio o ▶: iniciar / detener. El color de los cables muestra la tensión (verde +, rojo −),",
            "    los puntos amarillos muestran la dirección y magnitud de la corriente (sentido convencional de la corriente).",
            "  • Puedes hacer clic en un interruptor para cambiarlo incluso durante la simulación.",
            "  • El osciloscopio representa la tensión y la corriente del componente seleccionado.",
            "  • El deslizador de velocidad ajusta cuánto tiempo simulado transcurre por segundo real.",
            "  • Los componentes sobrecargados se marcan con ⚠ (potencia de la resistencia, corriente del LED/diodo, tensión del condensador).",
            "",
            "MODELOS",
            "  • Análisis nodal modificado (MNA); condensador y bobina con el método trapezoidal o Euler implícito;",
            "    diodo / LED con la ecuación de Shockley mediante iteración de Newton-Raphson, con ruptura Zener.");

    private static final String SUGO_PL = String.join("\n",
            "BUDOWANIE OBWODU",
            "  • Wybierz element z paska narzędzi, naciśnij przycisk myszy na siatce i przeciągnij do drugiego zacisku.",
            "  • Elementy łączą się ze sobą zaciskami (czarny punkt oznacza zbiegnięcie się 3 lub więcej zacisków,",
            "    czerwony okrąg oznacza niepodłączony zacisk).",
            "  • W trybie wyboru (Esc) kliknięcie wybiera, przeciąganie przesuwa; niebieskie kwadraty pozwalają przeciągnąć zacisk.",
            "  • Del: usuwanie, R: obrót o 90°, Ctrl+D: duplikowanie, Ctrl+Z / Ctrl+Y: wstecz / powtórz.",
            "  • Przeciąganie prawym lub środkowym przyciskiem myszy przesuwa widok, kółko myszy powiększa/zmniejsza.",
            "",
            "PARAMETRY",
            "  • Parametry wybranego elementu edytuje się w panelu po prawej stronie (zatwierdzane klawiszem Enter).",
            "  • Można używać przedrostków SI: p, n, u (µ), m, k, M, G – np. 4.7k, 100n, 2,2M.",
            "  • Polaryzacja: w baterii i źródle zacisk B (drugi) jest dodatni; w diodzie A to anoda, B to katoda.",
            "",
            "SYMULACJA",
            "  • Spacja lub ▶: start / stop. Kolor przewodów pokazuje napięcie (zielony +, czerwony −),",
            "    żółte punkty pokazują kierunek i wielkość prądu (konwencjonalny kierunek prądu).",
            "  • Możesz kliknąć przełącznik, aby go przełączyć, nawet w trakcie symulacji.",
            "  • Oscyloskop rysuje napięcie i prąd wybranego elementu.",
            "  • Suwak prędkości ustala, ile symulowanego czasu mija w ciągu jednej rzeczywistej sekundy.",
            "  • Przeciążone elementy są oznaczone ⚠ (moc rezystora, prąd LED/diody, napięcie kondensatora).",
            "",
            "MODELE",
            "  • Zmodyfikowana analiza węzłowa (MNA); kondensator i cewka metodą trapezów lub niejawną metodą Eulera;",
            "    dioda / LED równaniem Shockleya z iteracją Newtona-Raphsona, z przebiciem Zenera.");
}
