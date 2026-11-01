# Áramkör-szimulátor

Elektronikai áramkörök építésére és szimulációjára alkalmas oktatási célú
alkalmazás (Java Swing). A megoldó módosított csomóponti analízist (MNA)
használ időléptetéssel: a kondenzátorok és tekercsek trapéz- vagy implicit
Euler-módszerrel, a diódák Newton–Raphson-iterációval (Shockley-modell,
Zener-letörés) számolódnak.

## Futtatás

A program saját, beágyazott Java futtatókörnyezettel rendelkezik, külön
Java-telepítés nem szükséges.

1. Nyisd meg a [`dist/AramkorSzimulator`](dist/AramkorSzimulator) mappát.
2. Indítsd el az **`AramkorSzimulator.exe`** fájlt (Windows).

### Fordítás forrásból

```
javac -encoding UTF-8 *.java
java AramkorSzimulator
```

## Alkatrészek

Vezeték, ellenállás, kondenzátor, tekercs, telep (egyenáramú forrás),
AC forrás, áramforrás, dióda (Shockley-modell, Zener-letörés), kapcsoló,
izzó, ampermérő, voltmérő, föld.

## Mappaszerkezet

- [`AramkorSzimulator.java`](AramkorSzimulator.java) — a teljes alkalmazás
  forráskódja (rácsos szerkesztő felület, MNA megoldó, alkatrész-modellek)
- [`I18n.java`](I18n.java) — többnyelvű felület
- [`dist/`](dist/) — futtatható Windows-csomag (exe + bundlingolt Java runtime)

## Licenc

A projekt a [PolyForm Noncommercial 1.0.0](LICENSE) licenc alatt áll: szabadon
nézhető, módosítható és terjeszthető, de csak nem kereskedelmi célra.
