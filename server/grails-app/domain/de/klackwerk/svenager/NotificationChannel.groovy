package de.klackwerk.svenager

/** Where alerts go: an email address or a webhook URL. */
class NotificationChannel {

    /** Public API identifier; the numeric id stays internal. */
    String uuid = UUID.randomUUID().toString()

    String name
    /** EMAIL or WEBHOOK */
    String type
    /** Email address or webhook URL, depending on type. */
    String target
    boolean enabled = true
    Date dateCreated

    static constraints = {
        uuid unique: true
        name blank: false, maxSize: 190, unique: true
        type inList: ['EMAIL', 'WEBHOOK']
        target blank: false, maxSize: 1000
    }
}
