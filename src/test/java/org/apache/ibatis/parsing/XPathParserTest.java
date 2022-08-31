/*
 *    Copyright 2009-2021 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.io.Resources;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

class XPathParserTest {
  private String resource = "resources/nodelet_test.xml";

  class Person{
    String firstName;
    String lastName;
    String career;
    String gender;
    Integer age;
    Integer salary;

    public Person(String firstName, String lastName, String career, String gender, Integer age, Integer salary) {
      this.firstName = firstName;
      this.lastName = lastName;
      this.career = career;
      this.gender = gender;
      this.age = age;
      this.salary = salary;
    }

    public String getFirstName() {
      return firstName;
    }

    public String getLastName() {
      return lastName;
    }

    public String getCareer() {
      return career;
    }

    public String getGender() {
      return gender;
    }

    public Integer getAge() {
      return age;
    }

    public Integer getSalary() {
      return salary;
    }

    @Override
    public String toString() {
      return "Person{" +
        "firstName='" + firstName + '\'' +
        ", lastName='" + lastName + '\'' +
        ", career='" + career + '\'' +
        ", gender='" + gender + '\'' +
        ", age=" + age +
        ", salary=" + salary +
        '}';
    }
  }

  @Test
  void myTest1(){
    /*String[] atp = {"Rafael Nadal", "Novak Djokovic",
      "Stanislas Wawrinka",
      "David Ferrer","Roger Federer",
      "Andy Murray","Tomas Berdych",
      "Juan Martin Del Potro"};
    List<String> players =  Arrays.asList(atp);

    // 以前的循环方式
    for (String player : players) {
      System.out.print(player + "; ");
    }
    System.out.println();
    // 使用 lambda 表达式以及函数操作(functional operation)
    players.forEach((player) -> System.out.print(player + "; "));
    System.out.println();
    // 在 Java 8 中使用双冒号操作符(double colon operator)
    players.forEach(System.out::println);*/

    /*String[] players = {"Rafael Nadal", "Novak Djokovic",
      "Stanislas Wawrinka", "David Ferrer",
      "Roger Federer", "Andy Murray",
      "Tomas Berdych", "Juan Martin Del Potro",
      "Richard Gasquet", "John Isner"};*/

    //通过map操作，可以改变流中元素的类型，以下代码将String流映射成了Integer流
    List<String> strList = Arrays.asList("stream", "map", "flatMap");

    /* 将字符串列表转成长度列表 */
    /*List<Integer> hashCodeList = strList.stream()
      .map(String::length)
      .collect(Collectors.toList());
    System.out.println(hashCodeList);*/

    /* 性能优化点，避免装箱 */
    /*int[] hashCodeArr = strList.stream()
      .mapToInt(String::length)
      .toArray();
    System.out.println(Arrays.toString(hashCodeArr));*/

    /* 选出所有的字符，并去重 */

    List<String[]> contextStrList = strList.stream()
      .map(str -> str.split(""))
      .distinct()
      .collect(Collectors.toList());
    System.out.println(Arrays.deepToString(contextStrList.toArray()));

    List<String> contextList = strList.stream()
      .map(str -> str.split(""))
      .flatMap(strArr -> Arrays.stream(strArr))
      .distinct()
      .collect(Collectors.toList());
    System.out.println(Arrays.toString(contextList.toArray()));
    /*  flatMap的效果是映射成流的内容
    [s, t, r, e, a, m, p, f, l, M] */

    // 1.1 使用匿名内部类根据 name 排序 players
    /*Arrays.sort(players, new Comparator<String>() {
      @Override
      public int compare(String s1, String s2) {
        return (s1.compareTo(s2));
      }
    });*/
    /*Arrays.sort(players, String::compareTo);
    System.out.println(Arrays.asList(players));*/

    /*List<Person> javaProgrammers = new ArrayList<Person>() {
      {
        add(new Person("Elsdon", "Jaycob", "Java programmer", "male", 43, 2000));
        add(new Person("Tamsen", "Brittany", "Java programmer", "female", 23, 1500));
        add(new Person("Floyd", "Donny", "Java programmer", "male", 33, 1800));
        add(new Person("Sindy", "Jonie", "Java programmer", "female", 32, 1600));
        add(new Person("Vere", "Hervey", "Java programmer", "male", 22, 1200));
        add(new Person("Maude", "Jaimie", "Java programmer", "female", 27, 1900));
        add(new Person("Shawn", "Randall", "Java programmer", "male", 30, 2300));
        add(new Person("Jayden", "Corrina", "Java programmer", "female", 35, 1700));
        add(new Person("Palmer", "Dene", "Java programmer", "male", 33, 2000));
        add(new Person("Addison", "Pam", "Java programmer", "female", 34, 1300));
      }
    };

    List<Person> phpProgrammers = new ArrayList<Person>() {
      {
        add(new Person("Jarrod", "Pace", "PHP programmer", "male", 34, 1550));
        add(new Person("Clarette", "Cicely", "PHP programmer", "female", 23, 1200));
        add(new Person("Victor", "Channing", "PHP programmer", "male", 32, 1600));
        add(new Person("Tori", "Sheryl", "PHP programmer", "female", 21, 1000));
        add(new Person("Osborne", "Shad", "PHP programmer", "male", 32, 1100));
        add(new Person("Rosalind", "Layla", "PHP programmer", "female", 25, 1300));
        add(new Person("Fraser", "Hewie", "PHP programmer", "male", 36, 1100));
        add(new Person("Quinn", "Tamara", "PHP programmer", "female", 21, 1000));
        add(new Person("Alvin", "Lance", "PHP programmer", "male", 38, 1600));
        add(new Person("Evonne", "Shari", "PHP programmer", "female", 40, 1800));
      }
    };*/

    /*System.out.println(javaProgrammers);
    System.out.println(phpProgrammers);*/
    //javaProgrammers.forEach((p) -> System.out.printf("%s %s %s %n",p.getFirstName(),p.getLastName(),p.getSalary() + p.getSalary() * 0.05));
    /*List<Person> sortedJavaProgrammers = phpProgrammers.stream().filter(p -> p.getSalary() > 1400).collect(Collectors.toList());
    sortedJavaProgrammers.forEach((p) -> System.out.printf("%s %s %n",p.getFirstName(),p.getLastName()));*/
    /*System.out.println("根据 name 排序,并显示前5个 Java programmers:");
    List<Person> sortedJavaProgrammers = javaProgrammers
      .stream()
      //.sorted((p, p2) -> (p.getFirstName().compareTo(p2.getFirstName())))
      .sorted(Comparator.comparing(Person::getFirstName))
      .limit(5)
      .collect(Collectors.toList());

    sortedJavaProgrammers.forEach((p) -> System.out.printf("%s %s; %n", p.getFirstName(), p.getLastName()));

    System.out.println("根据 salary 排序 Java programmers:");
    sortedJavaProgrammers = javaProgrammers
      .stream()
      .sorted( (p, p2) -> (p.getSalary() - p2.getSalary()) )
      .collect( Collectors.toList());

    sortedJavaProgrammers.forEach((p) -> System.out.printf("%s %s; %n", p.getFirstName(), p.getLastName()));*/
  }

  @Test
  void myTest() throws Exception {

    try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
      XPathParser parser = new XPathParser(inputStream, false, null, null);
      //testEvalMethod(parser);
      XNode xNode = parser.evalNode("/sql");
      //System.out.println(xNode.getChildrenAsProperties());
      //System.out.println(xNode.getStringAttribute("resource"));
      //for(XNode child : xNode.getChildren()){
        //System.out.println(child.getName());
        //System.out.println(child.evalNode("dataSource").getStringAttribute("type"));
        //System.out.println(child.evalNode("dataSource").getChildrenAsProperties());
      //}
      //System.out.println(xNode.getChildren().size());
      //System.out.println(xNode.getChildren().size());
      /*XNode first_name = xNode.evalNode("setting");
      System.out.println(first_name);
      System.out.println(first_name.getChildrenAsProperties());*/
      //System.out.println(xNode.getNode().getNodeType()  == Node.ELEMENT_NODE);
      //System.out.println("include".equals(xNode.getNode().getNodeName()));
      //System.out.println(xNode.getChildren().size());
     /* NodeList children = xNode.getNode().getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        //System.out.println(children.item(i).getNodeType() == Node.ELEMENT_NODE);
        if("include".equals(children.item(i).getNodeName())){
          System.out.println(children.item(i).getAttributes().getNamedItem("refid").getNodeValue());
        }
      }*/
      //System.out.println(children);

      NamedNodeMap attributes = xNode.getNode().getAttributes();
      /*for (int i = 0; i < attributes.getLength(); i++) {
        Node attr = attributes.item(i);
        System.out.println(attr);
        //attr.setNodeValue(PropertyParser.parse(attr.getNodeValue(), variablesContext));
      }*/
      //System.out.println(xNode.getNode().getChildNodes().item(0).getNodeValue());
      System.out.println(xNode.getNode().getChildNodes().item(0).getOwnerDocument());
    }
  }

  // InputStream Source
  @Test
  void constructorWithInputStreamValidationVariablesEntityResolver() throws Exception {

    try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
      XPathParser parser = new XPathParser(inputStream, false, null, null);
      testEvalMethod(parser);
    }
  }

  @Test
  void constructorWithInputStreamValidationVariables() throws IOException {
    try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
      XPathParser parser = new XPathParser(inputStream, false, null);
      testEvalMethod(parser);
    }
  }

  @Test
  void constructorWithInputStreamValidation() throws IOException {
    try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
      XPathParser parser = new XPathParser(inputStream, false);
      testEvalMethod(parser);
    }
  }

  @Test
  void constructorWithInputStream() throws IOException {
    try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
      XPathParser parser = new XPathParser(inputStream);
      testEvalMethod(parser);
    }
  }

  // Reader Source
  @Test
  void constructorWithReaderValidationVariablesEntityResolver() throws Exception {

    try (Reader reader = Resources.getResourceAsReader(resource)) {
      XPathParser parser = new XPathParser(reader, false, null, null);
      testEvalMethod(parser);
    }
  }

  @Test
  void constructorWithReaderValidationVariables() throws IOException {
    try (Reader reader = Resources.getResourceAsReader(resource)) {
      XPathParser parser = new XPathParser(reader, false, null);
      testEvalMethod(parser);
    }
  }

  @Test
  void constructorWithReaderValidation() throws IOException {
    try (Reader reader = Resources.getResourceAsReader(resource)) {
      XPathParser parser = new XPathParser(reader, false);
      testEvalMethod(parser);
    }
  }

  @Test
  void constructorWithReader() throws IOException {
    try (Reader reader = Resources.getResourceAsReader(resource)) {
      XPathParser parser = new XPathParser(reader);
      testEvalMethod(parser);
    }
  }

  // Xml String Source
  @Test
  void constructorWithStringValidationVariablesEntityResolver() throws Exception {
    XPathParser parser = new XPathParser(getXmlString(resource), false, null, null);
    testEvalMethod(parser);
  }

  @Test
  void constructorWithStringValidationVariables() throws IOException {
    XPathParser parser = new XPathParser(getXmlString(resource), false, null);
    testEvalMethod(parser);
  }

  @Test
  void constructorWithStringValidation() throws IOException {
    XPathParser parser = new XPathParser(getXmlString(resource), false);
    testEvalMethod(parser);
  }

  @Test
  void constructorWithString() throws IOException {
    XPathParser parser = new XPathParser(getXmlString(resource));
    testEvalMethod(parser);
  }

  // Document Source
  @Test
  void constructorWithDocumentValidationVariablesEntityResolver() {
    XPathParser parser = new XPathParser(getDocument(resource), false, null, null);
    testEvalMethod(parser);
  }

  @Test
  void constructorWithDocumentValidationVariables() {
    XPathParser parser = new XPathParser(getDocument(resource), false, null);
    testEvalMethod(parser);
  }

  @Test
  void constructorWithDocumentValidation() {
    XPathParser parser = new XPathParser(getDocument(resource), false);
    testEvalMethod(parser);
  }

  @Test
  void constructorWithDocument() {
    XPathParser parser = new XPathParser(getDocument(resource));
    testEvalMethod(parser);
  }

  private Document getDocument(String resource) {
    try {
      InputSource inputSource = new InputSource(Resources.getResourceAsReader(resource));
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      factory.setIgnoringComments(true);
      factory.setIgnoringElementContentWhitespace(false);
      factory.setCoalescing(false);
      factory.setExpandEntityReferences(true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      return builder.parse(inputSource);// already closed resource in builder.parse method
    } catch (Exception e) {
      throw new BuilderException("Error creating document instance.  Cause: " + e, e);
    }
  }

  private String getXmlString(String resource) throws IOException {
    try (BufferedReader bufferedReader = new BufferedReader(Resources.getResourceAsReader(resource))) {
      StringBuilder sb = new StringBuilder();
      String temp;
      while ((temp = bufferedReader.readLine()) != null) {
        sb.append(temp);
      }
      return sb.toString();
    }
  }

  enum EnumTest {
    YES, NO
  }

  private void testEvalMethod(XPathParser parser) {
    assertEquals((Long) 1970L, parser.evalLong("/employee/birth_date/year"));
    assertEquals((Long) 1970L, parser.evalNode("/employee/birth_date/year").getLongBody());
    assertEquals((short) 6, (short) parser.evalShort("/employee/birth_date/month"));
    assertEquals((Integer) 15, parser.evalInteger("/employee/birth_date/day"));
    assertEquals((Integer) 15, parser.evalNode("/employee/birth_date/day").getIntBody());
    assertEquals((Float) 5.8f, parser.evalFloat("/employee/height"));
    assertEquals((Float) 5.8f, parser.evalNode("/employee/height").getFloatBody());
    assertEquals((Double) 5.8d, parser.evalDouble("/employee/height"));
    assertEquals((Double) 5.8d, parser.evalNode("/employee/height").getDoubleBody());
    assertEquals((Double) 5.8d, parser.evalNode("/employee").evalDouble("height"));
    assertEquals("${id_var}", parser.evalString("/employee/@id"));
    assertEquals("${id_var}", parser.evalNode("/employee/@id").getStringBody());
    assertEquals("${id_var}", parser.evalNode("/employee").evalString("@id"));
    assertEquals(Boolean.TRUE, parser.evalBoolean("/employee/active"));
    assertEquals(Boolean.TRUE, parser.evalNode("/employee/active").getBooleanBody());
    assertEquals(Boolean.TRUE, parser.evalNode("/employee").evalBoolean("active"));
    assertEquals(EnumTest.YES, parser.evalNode("/employee/active").getEnumAttribute(EnumTest.class, "bot"));
    assertEquals((Float) 3.2f, parser.evalNode("/employee/active").getFloatAttribute("score"));
    assertEquals((Double) 3.2d, parser.evalNode("/employee/active").getDoubleAttribute("score"));

    assertEquals("<id>${id_var}</id>", parser.evalNode("/employee/@id").toString().trim());
    assertEquals(7, parser.evalNodes("/employee/*").size());
    XNode node = parser.evalNode("/employee/height");
    assertEquals("employee/height", node.getPath());
    assertEquals("employee[${id_var}]_height", node.getValueBasedIdentifier());
  }

  @Test
  void formatXNodeToString() {
    XPathParser parser = new XPathParser("<users><user><id>100</id><name>Tom</name><age>30</age><cars><car index=\"1\">BMW</car><car index=\"2\">Audi</car><car index=\"3\">Benz</car></cars></user></users>");
    String usersNodeToString = parser.evalNode("/users").toString();
    String userNodeToString = parser.evalNode("/users/user").toString();
    String carsNodeToString = parser.evalNode("/users/user/cars").toString();

    String usersNodeToStringExpect =
      "<users>\n" +
      "    <user>\n" +
      "        <id>100</id>\n" +
      "        <name>Tom</name>\n" +
      "        <age>30</age>\n" +
      "        <cars>\n" +
      "            <car index=\"1\">BMW</car>\n" +
      "            <car index=\"2\">Audi</car>\n" +
      "            <car index=\"3\">Benz</car>\n" +
      "        </cars>\n" +
      "    </user>\n" +
      "</users>\n";

    String userNodeToStringExpect =
      "<user>\n" +
      "    <id>100</id>\n" +
      "    <name>Tom</name>\n" +
      "    <age>30</age>\n" +
      "    <cars>\n" +
      "        <car index=\"1\">BMW</car>\n" +
      "        <car index=\"2\">Audi</car>\n" +
      "        <car index=\"3\">Benz</car>\n" +
      "    </cars>\n" +
      "</user>\n";

  String carsNodeToStringExpect =
      "<cars>\n" +
      "    <car index=\"1\">BMW</car>\n" +
      "    <car index=\"2\">Audi</car>\n" +
      "    <car index=\"3\">Benz</car>\n" +
      "</cars>\n";

    assertEquals(usersNodeToStringExpect, usersNodeToString);
    assertEquals(userNodeToStringExpect, userNodeToString);
    assertEquals(carsNodeToStringExpect, carsNodeToString);
  }

}
