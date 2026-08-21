package de.klackwerk.svenager

class JobLogChunk {

    Job job
    int seq
    String content
    Date dateCreated

    static constraints = {
        seq min: 0, unique: 'job'
        content blank: false
    }

    static mapping = {
        content type: 'text'
    }
}
