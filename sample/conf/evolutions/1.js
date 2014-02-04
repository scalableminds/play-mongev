// A simple test evolution

// --- !Ups
db.test.insert({"text" : "It works!"})

// --- !Downs
db.test.remove()