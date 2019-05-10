type Person record {|
    string name = "";
    int age = 0;
    string...;
|};

function invalidRestField() {
    Person p = {name:"John", age:20, height:6, employed:false, city:"Colombo"};
}

type PersonA record {
    string name = "";
    int age = 0;
};

function emptyRecordForAnyRestField() {
    PersonA p = {name:"John", misc:{}};
}

type Pet record {
    Animal lion;
};

type Bar object {
    int a = 0;
};

function testInvalidRestFieldAddition() {
    PersonA p = {};
    p.invField = new Bar();
}

type Baz record {|
    int a;
    anydata...;
|};

type MyError error<string, map<error>>;

function testErrorAdditionForInvalidRestField() {
    error e1 = error("test reason");
    MyError e2 = error("test reason 2", { err: e1 });
    Baz b = { a: 1 };
    b.err1 = e1;
    b.err2 = e2;
}