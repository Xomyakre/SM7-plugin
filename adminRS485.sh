#!/bin/sh

java -Djava.library.path=. -cp "Clientboardrs485plugin-10.2.jar:lib/RXTXcomm.jar" ru.apertum.cbrs485.test.TestRS
