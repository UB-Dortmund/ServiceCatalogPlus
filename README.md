---

![TU Dortmund Logo](http://www.ub.tu-dortmund.de/images/tu-logo.png)

![UB Dortmund Logo](http://www.ub.tu-dortmund.de/images/ub-schriftzug.jpg)

---

# Service "CatalogPlus" - Eine Fassade für einen *Resource Discovery Index*

Hierbei handelt es sich um das von Drittanbietern unabhängige Framework des [*Katalog plus* der Universitätsbibliothek Dortmund](http://www.ub.tu-dortmund.de/katalog/).

Die Anpassungen an die Produkte erfolgt über die Interfaces:

* `de.tu_dortmund.ub.service.catalogplus.rds.ResourceDiscoveryService`
* `de.tu_dortmund.ub.service.catalogplus.vcs.VirtualClassificationSystem`
* `de.tu_dortmund.ub.util.output.ObjectToHtmlTransformation`

Die Konfiguration erfolgt über eine `properties`-Datei sowie über "META-INF/service".
