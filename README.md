# Engine 2 – Logic & Rules

Owns business services, application initialization/configuration and shared business utilities (geolocation, Levenshtein matching, HDMC data, password/OTP/ID helpers).

**Source layout:** `source/backend/src/main/java/com/civicsense/service/`, `.../config/`, selected `.../util/`, and the original Maven `pom.xml`.

This is a component repository. It depends on Engine 1 models/DAOs and is called by Engine 3 controllers. `mail.properties` has mail username/password replaced with placeholders. Supply secrets locally; do not commit them.
